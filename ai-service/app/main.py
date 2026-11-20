"""NexusOps AI service: local embeddings, extractive cited answers and ticket triage.
Stateless and offline: no database, no API keys, no hosted model."""

import threading
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import generation, triage
from app.embeddings import MODEL_LABEL, Embedder, FastEmbedEmbedder
from app.schemas import (
    AnswerIn,
    AnswerOut,
    EmbedIn,
    EmbedOut,
    HealthOut,
    TriageIn,
    TriageOut,
)


def create_app(
    embedder: Embedder | None = None,
    load_embedder: Callable[[], Embedder] = FastEmbedEmbedder,
) -> FastAPI:
    state: dict[str, Embedder] = {}
    lock = threading.Lock()
    if embedder is not None:
        state["embedder"] = embedder

    def get_embedder() -> Embedder:
        # Loaded at start-up (below); the fallback covers an app used without its lifespan.
        with lock:
            if "embedder" not in state:
                state["embedder"] = load_embedder()
            return state["embedder"]

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        # Load the model and run one embed before uvicorn accepts connections, so /health
        # answering means ready and the first user request isn't slow. Creating or importing the
        # app never loads it, and an injected embedder (tests) is used as is.
        get_embedder().embed(["warm up"])
        yield

    app = FastAPI(title="NexusOps AI Service", version="0.2.0", lifespan=lifespan)

    @app.get("/health", response_model=HealthOut, response_model_by_alias=True)
    def health() -> HealthOut:
        return HealthOut(
            status="ok", service="ai-service", provider="LOCAL", embedding_model=MODEL_LABEL
        )

    @app.post("/embed", response_model=EmbedOut, response_model_by_alias=True)
    def embed(body: EmbedIn) -> EmbedOut:
        vectors = get_embedder().embed(body.texts)
        return EmbedOut(vectors=vectors.tolist())

    @app.post("/answer", response_model=AnswerOut, response_model_by_alias=True)
    def answer(body: AnswerIn) -> AnswerOut:
        return generation.answer(get_embedder(), body.task, body.question, body.passages)

    @app.post("/triage", response_model=TriageOut, response_model_by_alias=True)
    def triage_ticket(body: TriageIn) -> TriageOut:
        return triage.triage(get_embedder(), body.subject, body.description, body.categories)

    return app


app = create_app()
