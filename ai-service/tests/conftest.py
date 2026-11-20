import re
import zlib

import numpy as np
import pytest

from app.embeddings import DIMENSIONS


class FakeEmbedder:
    """Deterministic bag-of-words hashing embedder: texts sharing words are similar."""

    def embed(self, texts: list[str]) -> np.ndarray:
        vectors = np.zeros((len(texts), DIMENSIONS), dtype=np.float32)
        for row, text in enumerate(texts):
            for word in re.findall(r"[a-z0-9']+", text.lower()):
                vectors[row, zlib.crc32(word.encode()) % DIMENSIONS] += 1.0
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        return vectors / np.where(norms == 0, 1, norms)


@pytest.fixture
def embedder() -> FakeEmbedder:
    return FakeEmbedder()
