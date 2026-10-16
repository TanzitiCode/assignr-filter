"""
The embedding model turns text into vectors (lists of numbers) that capture meaning.
Both ingest.py (documents) and rules_rag.py (questions) must use the SAME model,
so it's defined once here.
"""
from functools import lru_cache

from langchain_huggingface import HuggingFaceEmbeddings

import config


@lru_cache(maxsize=1)
def get_embeddings() -> HuggingFaceEmbeddings:
    return HuggingFaceEmbeddings(
        model_name=config.EMBEDDING_MODEL,
        encode_kwargs={"normalize_embeddings": True},  # makes similarity scores comparable
    )
