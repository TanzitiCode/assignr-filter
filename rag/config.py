"""
Shared settings for the referee RAG assistant.

Everything that might change (paths, model names, chunk sizes) lives here so the
other files don't hard-code it. Model names can be overridden with environment variables.
"""
import os
from pathlib import Path

RAG_DIR = Path(__file__).parent

# Where you put your documents:
#   docs/laws/              -> IFAB Laws of the Game (+ federation memos / Q&As)
#   docs/leagues/<LEAGUE>/  -> that league's rules, e.g. docs/leagues/ECNL/ecnl-rules.pdf
DOCS_DIR = RAG_DIR / "docs"
LAWS_DIR = DOCS_DIR / "laws"
LEAGUES_DIR = DOCS_DIR / "leagues"

# Where the vector index is saved (rebuilt by ingest.py)
INDEX_DIR = RAG_DIR / "index"
COLLECTION_NAME = "referee_docs"

# Games exported by the Java scraper (Main.java writes it to the project root)
ASSIGNMENTS_FILE = RAG_DIR.parent / "assignments.json"
# Warn when the export is older than this — open games get taken quickly
ASSIGNMENTS_STALE_HOURS = 12

# File types ingest.py knows how to read
SUPPORTED_EXTENSIONS = {".pdf", ".md", ".txt"}

# Embedding model — runs locally and is free (downloaded once on first use).
# If you change this, re-run ingest.py: vectors from different models aren't comparable.
EMBEDDING_MODEL = os.getenv("RAG_EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5")

# The LLM that writes answers. Needs ANTHROPIC_API_KEY set.
LLM_MODEL = os.getenv("RAG_LLM_MODEL", "claude-sonnet-5-5")

# Chunking — the video's "split" step. ~1000 characters is roughly one Law sub-section.
CHUNK_SIZE = 1000
CHUNK_OVERLAP = 150

# How many chunks to retrieve per question (the video's "k").
# With multi-query, each version of the question retrieves TOP_K, and the fused top TOP_K go to the LLM.
TOP_K = 6

# Multi-query (video 0:22): how many rewrites of the question to search, in addition to the original.
# Each rewrite costs one extra embedding search; the rewrites themselves come from one LLM call.
MULTI_QUERY_COUNT = 4

# Adaptive RAG (video 1:26–2:12): self-checking loop limits for rules questions.
# MAX_REWRITES: times the search question may be rewritten when no relevant rules are found.
# MAX_GENERATIONS: answer attempts when the checker finds unsupported claims.
MAX_REWRITES = 1
MAX_GENERATIONS = 2
