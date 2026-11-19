"""NexusOps AI service: local embeddings, extractive cited answers and ticket triage.
Stateless and offline: no database, no API keys, no hosted model."""

import threading

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


def create_app(embedder: Embedder | None = None) -> FastAPI:
    app = FastAPI(title="NexusOps AI Service", version="0.2.0")
    state: dict[str, Embedder] = {}
    lock = threading.Lock()
    if embedder is not None:
        state["embedder"] = embedder

    def get_embedder() -> Embedder:
        # Created lazily on first use so start-up and tests never load the model.
        with lock:
            if "embedder" not in state:
                state["embedder"] = FastEmbedEmbedder()
            return state["embedder"]

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
