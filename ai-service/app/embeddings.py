"""Local text embeddings (fastembed, BAAI/bge-small-en-v1.5). No network at runtime: the model
files are baked into the image at build time."""

from typing import Protocol

import numpy as np

MODEL_NAME = "BAAI/bge-small-en-v1.5"
MODEL_LABEL = "bge-small-en-v1.5"
DIMENSIONS = 384


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> np.ndarray:
        """Return an array of shape (len(texts), 384) whose rows are L2-normalised."""
        ...


class FastEmbedEmbedder:
    def __init__(self) -> None:
        from fastembed import TextEmbedding  # lazy: tests never load the model

        self._model = TextEmbedding(MODEL_NAME)

    def embed(self, texts: list[str]) -> np.ndarray:
        vectors = np.array(list(self._model.embed(texts)), dtype=np.float32)
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        return vectors / np.where(norms == 0, 1, norms)
