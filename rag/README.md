# Referee RAG assistant

Answers rules questions using the Laws of the Game and your leagues' rule documents,
with citations. Built following "RAG From Scratch" (LangChain).

## Setup (once)

```bash
cd rag
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...      # add to ~/.zshrc to keep it
```

## Use

1. Put documents in `docs/` (see [docs/README.md](docs/README.md)).
2. Build the index (re-run whenever documents change): `python ingest.py`
3. Ask:
   ```bash
   python ask.py "Keeper handles a deliberate kick from a teammate. Restart?"
   python ask.py --league ECNL "How long are halves for U15?"
   python ask.py "Any varsity center games this Saturday over $60?"   # needs assignments.json from the Java scraper
   python ask.py            # interactive
   ```
   Add `--show-context` to see the routing, rewritten queries, self-check steps, and retrieved chunks.

## Files

| File | Video section | What it does |
|---|---|---|
| `ingest.py` | Indexing | Loads docs, splits into chunks, embeds, saves to `index/` |
| `rules_rag.py` | Retrieval, Generation, Multi-Query, RAG Fusion | Rewrites the question, finds relevant chunks (Laws + chosen league), writes a cited answer |
| `rules_graph.py` | CRAG + Adaptive RAG | Self-checking loop: grades retrieved rules, verifies the answer is supported, retries or says "not found" |
| `router.py` | Routing | Decides if a question is about rules, your Assignr games, or general advice, and detects the league |
| `assignments.py` | Query Construction | Turns a question about your games into filters, runs them on `assignments.json` |
| `assistant.py` | — | Sends each question to the right handler based on the route |
| `ask.py` | — | Command-line interface |
| `embeddings.py` | Indexing | The local embedding model (shared by ingest and search) |
| `config.py` | — | Paths, model names, chunk size, `k` |
