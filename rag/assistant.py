"""
Ties the pieces together: route the question, then hand it to the right handler.

    question -> router -> rules        -> rules_graph.answer (multi-query + RAG Fusion + self-checks)
                                          or rules_rag.answer with adaptive=False (no checks)
                       -> assignments  -> assignments.answer (query construction over assignments.json)
                       -> general      -> plain LLM answer, labeled as not from the rulebooks
"""
from dataclasses import dataclass, field
from typing import Optional

from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate

import assignments
import rules_graph
import rules_rag
from router import Route, route_question

GENERAL_PROMPT = ChatPromptTemplate.from_messages([
    ("system", """You are an assistant for a soccer referee. This question isn't covered by their rulebooks,
so answer from general refereeing knowledge. Be practical and concise.
If the question is actually about a specific Law or league rule, say they should ask it as a rules question
so it can be checked against their documents — don't state specific rule wording from memory."""),
    ("human", "{question}"),
])


@dataclass
class Result:
    route: Route
    text: str
    docs: list[Document] = field(default_factory=list)
    queries: list[str] = field(default_factory=list)
    league_used: Optional[str] = None
    games: Optional[assignments.GameAnswer] = None
    status: str = "ok"                      # rules only: "ok" | "unverified" | "not_found"
    trace: list[str] = field(default_factory=list)  # rules only: what the self-checking loop did


def handle(
    store: Optional[Chroma],
    question: str,
    leagues: list[str],
    league_override: Optional[str] = None,
    multi_query: bool = True,
    llm: Optional[BaseChatModel] = None,
    adaptive: bool = True,
) -> Result:
    llm = llm or rules_rag.get_llm()
    route = route_question(question, leagues, llm)

    if route.destination == "rules":
        # A league chosen with --league / `league X` wins over one detected in the question
        if store is None:
            return Result(route, "No rulebook index found. Add documents to rag/docs/ and run `python ingest.py`.")
        league = league_override or route.league
        if not adaptive:
            text, docs, queries = rules_rag.answer(store, question, league, multi_query, llm=llm)
            return Result(route, text, docs, queries, league)
        s = rules_graph.answer(store, question, league, multi_query, llm=llm)
        return Result(route, s["answer"], s.get("docs", []), s.get("queries", []), league,
                      status=s.get("status", "ok"), trace=s.get("trace", []))

    if route.destination == "assignments":
        try:
            games = assignments.answer(question, llm)
        except FileNotFoundError as e:
            return Result(route, str(e))
        return Result(route, games.text, games=games)

    text = (GENERAL_PROMPT | llm | StrOutputParser()).invoke({"question": question})
    return Result(route, "(General advice — not from your rulebooks.)\n\n" + text)
