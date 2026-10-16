"""
RETRIEVAL (video 0:10) + GENERATION (video 0:15) for rules questions,
improved with MULTI-QUERY (video 0:22) and RAG FUSION ranking (video 0:28).

Flow:
  1. Multi-query: the LLM rewrites the question several ways using different referee
     terminology ("DOGSO" vs "denying an obvious goal-scoring opportunity").
  2. Each version is searched separately (Laws + chosen league).
  3. RAG Fusion: the separate result lists are merged with Reciprocal Rank Fusion, so
     chunks that rank well for several versions of the question come out on top.
  4. The top chunks go to the LLM, which answers using only those chunks.
"""
import re
from itertools import zip_longest
from typing import Optional

from langchain_anthropic import ChatAnthropic
from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_core.language_models import BaseChatModel
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate

import config
from embeddings import get_embeddings

ANSWER_TEMPLATE = """You are an assistant for a soccer referee. Answer the question using ONLY the context below.

Rules for answering:
- Cite every rule you use like [file p.N]. If the context doesn't contain the answer, say so plainly — never guess.
- League rules can modify the Laws of the Game (e.g. game length, substitutions, heading restrictions).
  If a league rule and the Laws differ, give the league rule for that league and say it differs from the Laws.
- Some competitions don't use the Laws of the Game at all (e.g. VHSL high school games use NFHS rules). If the
  league's documents say so, answer from those rules and use the Laws only for comparison.
- Rules often differ by age group. If the answer depends on the age group and none was given, list each age group.
- If the answer depends on the league and no league was given, say what it depends on.
- Be concise: the decision first (e.g. the sanction and restart), then the reasoning.

League: {league}

Context:
{context}

Question: {question}"""
ANSWER_PROMPT = ChatPromptTemplate.from_template(ANSWER_TEMPLATE)

MULTI_QUERY_PROMPT = ChatPromptTemplate.from_template(
    """You help a soccer referee search the Laws of the Game and league rulebooks.
Rewrite the question below in {n} different ways so a search finds the right rule. Vary the wording:
- Use both referee shorthand and the rulebook's formal wording
  (e.g. "DOGSO" / "denying an obvious goal-scoring opportunity", "back pass" / "ball deliberately kicked to the goalkeeper by a team-mate",
   "PK" / "penalty kick", "AR" / "assistant referee", "caution" / "yellow card").
- Name the likely Law and topic (e.g. "Law 12 Fouls and Misconduct", "Law 7 Duration of the Match").
- If the question is about a league's format (game length, substitutions, roster, age groups), phrase one version as a league rule lookup.

Return ONLY the {n} questions, one per line, with no numbering or extra text.

Question: {question}"""
)


def get_llm() -> ChatAnthropic:
    # Note: newer Claude models reject non-default temperature, so it is left at the default
    return ChatAnthropic(model=config.LLM_MODEL)


def load_vectorstore() -> Chroma:
    if not config.INDEX_DIR.exists():
        raise SystemExit("No index found. Run `python ingest.py` first.")
    return Chroma(
        collection_name=config.COLLECTION_NAME,
        embedding_function=get_embeddings(),
        persist_directory=str(config.INDEX_DIR),
    )


def known_leagues(store: Chroma) -> list[str]:
    metas = store.get(include=["metadatas"])["metadatas"]
    return sorted({m["league"] for m in metas if m.get("league")})


# ── Step 1: multi-query ────────────────────────────────────────────────────────

def generate_queries(question: str, llm: BaseChatModel) -> list[str]:
    """Original question + N rewrites. The original is always kept in case the rewrites drift."""
    chain = MULTI_QUERY_PROMPT | llm | StrOutputParser()
    raw = chain.invoke({"question": question, "n": config.MULTI_QUERY_COUNT})
    rewrites = []
    for line in raw.splitlines():
        line = re.sub(r"^\s*(\d+[.)]|[-*•])\s*", "", line).strip()  # strip "1." / "-" if the model adds them
        if line and line.lower() != question.lower():
            rewrites.append(line)
    return [question] + rewrites[: config.MULTI_QUERY_COUNT]


# ── Step 2: search each query ──────────────────────────────────────────────────

def search(store: Chroma, query: str, league: Optional[str]) -> list[Document]:
    """Search the Laws plus (if given) that one league's rules. No league -> search everything."""
    search_filter = None
    if not league:
        return store.similarity_search(query, k=config.TOP_K)
    # Search the league and the Laws SEPARATELY, then interleave (league #1, Laws #1, league #2, ...).
    # In one combined search the 400+ chunk Laws book crowds out a league's few pages of modifications.
    league_docs = store.similarity_search(query, k=config.TOP_K, filter={"league": league})
    laws_docs = store.similarity_search(query, k=config.TOP_K, filter={"source_type": "laws"})
    mixed = [d for pair in zip_longest(league_docs, laws_docs) for d in pair if d is not None]
    return mixed[: config.TOP_K]


# ── Step 3: RAG Fusion ─────────────────────────────────────────────────────────

def doc_key(d: Document) -> tuple:
    return (d.metadata.get("file"), d.metadata.get("page"), d.page_content)


def reciprocal_rank_fusion(result_lists: list[list[Document]], k: int = 60) -> list[Document]:
    """
    Merge several ranked lists into one. Each chunk scores 1/(k + rank) per list it appears in,
    so a chunk ranked well by many query versions beats one ranked #1 by a single version.
    k=60 is the standard value from the original RRF paper (and the video).
    """
    scores: dict[tuple, float] = {}
    docs: dict[tuple, Document] = {}
    for results in result_lists:
        for rank, d in enumerate(results):
            key = doc_key(d)
            docs[key] = d
            scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank + 1)
    ranked = sorted(scores, key=scores.get, reverse=True)
    return [docs[key] for key in ranked]


def retrieve(
    store: Chroma,
    question: str,
    league: Optional[str] = None,
    llm: Optional[BaseChatModel] = None,
    multi_query: bool = True,
) -> tuple[list[Document], list[str]]:
    """Returns (top chunks, the queries that were searched)."""
    if not multi_query:
        return search(store, question, league), [question]
    queries = generate_queries(question, llm or get_llm())
    fused = reciprocal_rank_fusion([search(store, q, league) for q in queries])
    return fused[: config.TOP_K], queries


# ── Step 4: generation ─────────────────────────────────────────────────────────

def format_docs(docs: list[Document]) -> str:
    parts = []
    for d in docs:
        m = d.metadata
        label = m["league"] or "Laws of the Game"
        page = f" p.{m['page']}" if m.get("page") else ""
        parts.append(f"[{label} — {m['file']}{page}]\n{d.page_content}")
    return "\n\n---\n\n".join(parts)


def answer(
    store: Chroma,
    question: str,
    league: Optional[str] = None,
    multi_query: bool = True,
    llm: Optional[BaseChatModel] = None,
) -> tuple[str, list[Document], list[str]]:
    """Returns (answer text, chunks used, queries searched)."""
    llm = llm or get_llm()
    docs, queries = retrieve(store, question, league, llm, multi_query)
    if not docs:
        return "I couldn't find anything relevant in your documents.", [], queries
    chain = ANSWER_PROMPT | llm | StrOutputParser()
    text = chain.invoke({
        "context": format_docs(docs),
        "question": question,
        "league": league or "not specified",
    })
    return text, docs, queries
