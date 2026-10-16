"""
INDEXING (video 0:05) — load documents, split them into chunks, embed, and store.

Run this whenever you add, remove, or update a document:
    python ingest.py

Each chunk is tagged with metadata so answers can cite sources and so we can
filter by league later:
    source_type: "laws" or "league"
    league:      folder name, e.g. "ECNL" ("" for the Laws)
    file:        file name
    page:        page number (PDFs only, 1-based)
"""
import logging
import shutil
import sys
from pathlib import Path

from langchain_chroma import Chroma
from langchain_community.document_loaders import PyPDFLoader, TextLoader
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

import config
from embeddings import get_embeddings

# pypdf warns about fonts it can't fully decode (e.g. in the IFAB PDF); the text still extracts fine
logging.getLogger("pypdf").setLevel(logging.ERROR)


def load_file(path: Path) -> list[Document]:
    if path.suffix.lower() == ".pdf":
        docs = PyPDFLoader(str(path)).load()
        for d in docs:
            d.metadata["page"] = d.metadata.get("page", 0) + 1  # PyPDF counts from 0
        return docs
    return TextLoader(str(path), encoding="utf-8").load()


def tag(docs: list[Document], source_type: str, league: str, path: Path) -> list[Document]:
    for d in docs:
        d.metadata = {
            "source_type": source_type,
            "league": league,
            "file": path.name,
            "page": d.metadata.get("page", 0),
        }
    return docs


def find_documents() -> list[tuple[Path, str, str]]:
    """Returns (path, source_type, league) for every supported file under docs/."""
    found = []
    for path in sorted(config.LAWS_DIR.rglob("*")):
        if path.suffix.lower() in config.SUPPORTED_EXTENSIONS:
            found.append((path, "laws", ""))
    for league_dir in sorted(p for p in config.LEAGUES_DIR.iterdir() if p.is_dir()):
        for path in sorted(league_dir.rglob("*")):
            if path.suffix.lower() in config.SUPPORTED_EXTENSIONS:
                found.append((path, "league", league_dir.name))
    return found


def main():
    files = find_documents()
    if not files:
        print(f"No documents found. Add PDFs/.md/.txt files under {config.DOCS_DIR}/ (see docs/README.md).")
        sys.exit(1)

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=config.CHUNK_SIZE, chunk_overlap=config.CHUNK_OVERLAP
    )

    chunks = []
    for path, source_type, league in files:
        try:
            docs = tag(load_file(path), source_type, league, path)
        except Exception as e:
            print(f"  SKIPPED {path.relative_to(config.DOCS_DIR)}: {e}")
            continue
        file_chunks = splitter.split_documents(docs)
        chunks.extend(file_chunks)
        label = league or "Laws of the Game"
        print(f"  {label:<20} {path.name:<45} {len(file_chunks):>4} chunks")

    if not chunks:
        print("Nothing could be loaded.")
        sys.exit(1)

    # Rebuild from scratch so deleted/updated files don't leave stale chunks behind
    if config.INDEX_DIR.exists():
        shutil.rmtree(config.INDEX_DIR)

    print(f"\nEmbedding {len(chunks)} chunks with {config.EMBEDDING_MODEL} (first run downloads the model)...")
    Chroma.from_documents(
        chunks,
        get_embeddings(),
        collection_name=config.COLLECTION_NAME,
        persist_directory=str(config.INDEX_DIR),
    )
    print(f"Done. Index saved to {config.INDEX_DIR}")


if __name__ == "__main__":
    main()
