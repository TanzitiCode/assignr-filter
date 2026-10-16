"""
ADAPTIVE RAG (video 1:26 CRAG + 1:44 Adaptive RAG) — rules answers with built-in checks.

The basic pipeline (rules_rag.answer) always answers, even when retrieval found the wrong
rules. For refereeing a confident wrong ruling is worse than "I don't know", so this
LangGraph state machine checks its own work:

    retrieve ──► grade_docs ──(some relevant)──► generate ──► check_answer ──(ok)──► END
        ▲            │                              ▲              │
        │      (none relevant)                      │       (not grounded) ──► generate again (once)
        │            ▼                              │              │
        └──── rewrite_question (once) ◄─────────────┼──── (doesn't answer the question)
                     │                                             │
               (already rewritten)                         (out of retries) ──► END with a warning
                     ▼
                 not_found ──► END

Changes from the video, to keep it fast and cheap:
- Documents are graded in ONE LLM call (the video grades each chunk separately).
- Groundedness ("hallucination") and usefulness are checked in ONE call.
- No web-search fallback (CRAG's): web answers about the Laws are often outdated or wrong,
  so it says it couldn't find the rule instead.
- Every loop is capped (config.MAX_REWRITES / MAX_GENERATIONS). A normal question costs 4 LLM calls
  (rewrite for multi-query, grade, answer, check); retries add more.
"""
from typing import Optional, TypedDict

from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, Field

import config
import rules_rag

# ── Graders (structured output = reliable yes/no the graph can branch on) ─────

class DocGrades(BaseModel):
    relevant: list[int] = Field(description="Numbers of the chunks that help answer the question")


class AnswerCheck(BaseModel):
    grounded: bool = Field(description="Every rule, sanction, restart, number, and time in the answer is stated in the chunks")
    answers_question: bool = Field(description="The answer addresses what was asked (saying the documents don't cover a part counts as addressing it)")
    problem: Optional[str] = Field(None, description="If either is false: what's unsupported or missing, in one sentence")


GRADE_PROMPT = ChatPromptTemplate.from_template(
    """A soccer referee asked a question. Which of these retrieved chunks contain information that helps answer it?
Count a chunk as relevant if it states a rule, definition, procedure, or league format the answer needs —
even if it only answers part of the question. A chunk on the same general topic that doesn't help is NOT relevant.

Question: {question}

{chunks}

Return the numbers of the relevant chunks (an empty list if none)."""
)

CHECK_PROMPT = ChatPromptTemplate.from_template(
    """Check a referee assistant's answer against the source chunks it was given.

Source chunks:
{context}

Question: {question}

Answer:
{answer}

grounded = true only if every specific claim (sanction, card, restart, distance, time, number, league rule) is supported by the chunks.
answers_question = true if it addresses what was asked."""
)

REWRITE_PROMPT = ChatPromptTemplate.from_template(
    """A search of the Laws of the Game and league rulebooks didn't find rules that answer this referee's question.
Rewrite it as one clear search question using the rulebooks' formal wording and naming the likely Law
(e.g. "Law 12 – Fouls and Misconduct", "Law 3 – The Players", "Law 7 – The Duration of the Match").
{reason}
Return only the rewritten question.

Original question: {question}"""
)

# The answer prompt plus a slot for feedback when a draft fails the check
GENERATE_PROMPT = ChatPromptTemplate.from_template(rules_rag.ANSWER_TEMPLATE + "{retry_note}")
RETRY_NOTE = ("\n\nIMPORTANT: a previous draft was rejected because: {problem}\n"
              "Only state what the context supports. If the context doesn't cover something, say so.")


def grade_docs(question: str, docs: list[Document], llm: BaseChatModel) -> list[Document]:
    chunks = "\n\n".join(f"[{i + 1}] {rules_rag.format_docs([d])}" for i, d in enumerate(docs))
    grader = GRADE_PROMPT | llm.with_structured_output(DocGrades, method="json_schema")
    picked = grader.invoke({"question": question, "chunks": chunks}).relevant
    return [d for i, d in enumerate(docs) if (i + 1) in set(picked)]


def check_answer(question: str, docs: list[Document], answer: str, llm: BaseChatModel) -> AnswerCheck:
    checker = CHECK_PROMPT | llm.with_structured_output(AnswerCheck, method="json_schema")
    return checker.invoke({"question": question, "context": rules_rag.format_docs(docs), "answer": answer})


def rewrite_question(question: str, llm: BaseChatModel, reason: str = "") -> str:
    return (REWRITE_PROMPT | llm | StrOutputParser()).invoke({"question": question, "reason": reason}).strip()


def generate(question: str, docs: list[Document], league: Optional[str], llm: BaseChatModel,
             problem: Optional[str] = None) -> str:
    return (GENERATE_PROMPT | llm | StrOutputParser()).invoke({
        "context": rules_rag.format_docs(docs),
        "question": question,
        "league": league or "not specified",
        "retry_note": RETRY_NOTE.format(problem=problem) if problem else "",
    })


# ── Graph state + nodes ────────────────────────────────────────────────────────

class State(TypedDict, total=False):
    question: str            # the referee's original question (always what gets answered)
    search_question: str     # what we search with (changes if rewritten)
    league: Optional[str]
    multi_query: bool
    docs: list[Document]
    queries: list[str]
    answer: str
    check: Optional[AnswerCheck]
    rewrites: int
    generations: int
    status: str              # "ok" | "unverified" | "not_found"
    trace: list[str]         # human-readable log of what happened


def build_graph(store: Chroma, llm: BaseChatModel):
    def log(state: State, msg: str) -> list[str]:
        return state.get("trace", []) + [msg]

    def retrieve_node(state: State) -> State:
        docs, queries = rules_rag.retrieve(store, state["search_question"], state.get("league"), llm, state.get("multi_query", True))
        return {"docs": docs, "queries": queries, "trace": log(state, f"retrieved {len(docs)} chunks")}

    def grade_node(state: State) -> State:
        if not state["docs"]:
            return {"docs": [], "trace": log(state, "nothing retrieved")}
        kept = grade_docs(state["question"], state["docs"], llm)
        return {"docs": kept, "trace": log(state, f"graded: {len(kept)}/{len(state['docs'])} relevant")}

    def rewrite_node(state: State) -> State:
        reason = ""
        if state.get("check") and state["check"].problem:
            reason = f"The last answer had this problem: {state['check'].problem}"
        new_q = rewrite_question(state["question"], llm, reason)
        return {"search_question": new_q, "rewrites": state.get("rewrites", 0) + 1,
                "trace": log(state, f"rewrote search: {new_q!r}")}

    def generate_node(state: State) -> State:
        problem = state["check"].problem if state.get("check") and not state["check"].grounded else None
        text = generate(state["question"], state["docs"], state.get("league"), llm, problem)
        n = state.get("generations", 0) + 1
        return {"answer": text, "generations": n, "trace": log(state, f"generated answer (attempt {n})")}

    def check_node(state: State) -> State:
        c = check_answer(state["question"], state["docs"], state["answer"], llm)
        msg = f"check: grounded={'yes' if c.grounded else 'no'}, answers={'yes' if c.answers_question else 'no'}"
        if c.problem:
            msg += f" ({c.problem})"
        return {"check": c, "trace": log(state, msg)}

    def not_found_node(state: State) -> State:
        where = f"the Laws of the Game or the {state['league']} rules" if state.get("league") else "your documents"
        return {"status": "not_found", "answer":
                f"I couldn't find a rule that answers this in {where}. "
                "It may not be covered, or the right document may not be indexed — check with your assignor or referee administrator.",
                "trace": log(state, "gave up: no relevant rules")}

    def finish_ok(state: State) -> State:
        return {"status": "ok"}

    def finish_unverified(state: State) -> State:
        warning = ("⚠ I couldn't fully verify this answer against your documents"
                   + (f" ({state['check'].problem})" if state.get("check") and state["check"].problem else "")
                   + ". Check the cited sources before relying on it.\n\n")
        return {"status": "unverified", "answer": warning + state["answer"], "trace": log(state, "returned with warning")}

    # ── Conditional edges ──
    def after_grade(state: State) -> str:
        if state["docs"]:
            return "generate"
        return "rewrite" if state.get("rewrites", 0) < config.MAX_REWRITES else "not_found"

    def after_check(state: State) -> str:
        c = state["check"]
        if c.grounded and c.answers_question:
            return "finish_ok"
        if not c.grounded and state.get("generations", 0) < config.MAX_GENERATIONS:
            return "generate"            # same docs, stricter prompt
        if not c.answers_question and state.get("rewrites", 0) < config.MAX_REWRITES:
            return "rewrite"             # probably retrieved the wrong rules
        return "finish_unverified"

    g = StateGraph(State)
    g.add_node("retrieve", retrieve_node)
    g.add_node("grade", grade_node)
    g.add_node("rewrite", rewrite_node)
    g.add_node("generate", generate_node)
    g.add_node("check", check_node)
    g.add_node("not_found", not_found_node)
    g.add_node("finish_ok", finish_ok)
    g.add_node("finish_unverified", finish_unverified)

    g.add_edge(START, "retrieve")
    g.add_edge("retrieve", "grade")
    g.add_conditional_edges("grade", after_grade, ["generate", "rewrite", "not_found"])
    g.add_edge("rewrite", "retrieve")
    g.add_edge("generate", "check")
    g.add_conditional_edges("check", after_check, ["finish_ok", "generate", "rewrite", "finish_unverified"])
    g.add_edge("not_found", END)
    g.add_edge("finish_ok", END)
    g.add_edge("finish_unverified", END)
    return g.compile()


def answer(store: Chroma, question: str, league: Optional[str] = None, multi_query: bool = True,
           llm: Optional[BaseChatModel] = None) -> State:
    graph = build_graph(store, llm or rules_rag.get_llm())
    return graph.invoke({
        "question": question, "search_question": question, "league": league,
        "multi_query": multi_query, "rewrites": 0, "generations": 0, "trace": [],
    })
