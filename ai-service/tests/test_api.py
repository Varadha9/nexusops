import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import FakeEmbedder


@pytest.fixture
def client() -> TestClient:
    return TestClient(create_app(FakeEmbedder()))


def test_health(client) -> None:
    body = client.get("/health").json()
    assert body == {
        "status": "ok",
        "service": "ai-service",
        "provider": "LOCAL",
        "embeddingModel": "bge-small-en-v1.5",
    }


def test_embed_shape_and_norm(client) -> None:
    response = client.post("/embed", json={"texts": ["hello world", "annual leave"]})
    assert response.status_code == 200
    vectors = np.array(response.json()["vectors"])
    assert vectors.shape == (2, 384)
    assert np.allclose(np.linalg.norm(vectors, axis=1), 1.0, atol=1e-5)


@pytest.mark.parametrize(
    "texts",
    [[], ["x"] * 65, [""], ["x" * 4001]],
)
def test_embed_rejects_bad_input(client, texts) -> None:
    assert client.post("/embed", json={"texts": texts}).status_code == 422


def test_embed_accepts_limits(client) -> None:
    assert client.post("/embed", json={"texts": ["x" * 4000] * 64}).status_code == 200


def test_answer_end_to_end(client) -> None:
    response = client.post(
        "/answer",
        json={
            "task": "POLICY_QUESTION",
            "question": "How many annual leave days?",
            "passages": [{"n": 2, "title": "Leave", "text": "You get twenty annual leave days."}],
        },
    )
    assert response.status_code == 200
    assert response.json() == {
        "answer": "You get twenty annual leave days. [2]",
        "citedPassages": [2],
        "grounded": True,
        "mode": "LOCAL",
        "model": "bge-small-en-v1.5",
        "promptVersion": "policy-v1",
        "inputTokens": 0,
        "outputTokens": 0,
    }


def test_answer_with_no_passages(client) -> None:
    response = client.post(
        "/answer", json={"task": "TICKET_REPLY", "question": "hello", "passages": []}
    )
    assert response.status_code == 200
    assert response.json()["citedPassages"] == []
    assert response.json()["promptVersion"] == "reply-v1"


@pytest.mark.parametrize(
    "body",
    [
        {"task": "POLICY_QUESTION", "question": "", "passages": []},
        {"task": "POLICY_QUESTION", "question": "x" * 4001, "passages": []},
        {"task": "OTHER", "question": "hi", "passages": []},
        {
            "task": "POLICY_QUESTION",
            "question": "hi",
            "passages": [{"n": 1, "title": "t", "text": "x"}] * 11,
        },
        {
            "task": "POLICY_QUESTION",
            "question": "hi",
            "passages": [{"n": 0, "title": "t", "text": "x"}],
        },
    ],
)
def test_answer_rejects_bad_input(client, body) -> None:
    assert client.post("/answer", json=body).status_code == 422


def test_triage_end_to_end(client) -> None:
    response = client.post(
        "/triage",
        json={
            "subject": "Network is down",
            "description": "",
            "categories": [{"id": "c1", "name": "Network"}, {"id": "c2", "name": "Billing"}],
        },
    )
    assert response.status_code == 200
    assert response.json() == {
        "categoryId": "c1",
        "priority": "URGENT",
        "reason": 'Mentions "down"; closest category: Network.',
        "mode": "LOCAL",
        "model": "bge-small-en-v1.5",
        "promptVersion": "triage-v1",
        "inputTokens": 0,
        "outputTokens": 0,
    }


@pytest.mark.parametrize(
    "body",
    [
        {"subject": "", "description": "", "categories": []},
        {"subject": "x" * 301, "description": "", "categories": []},
        {"subject": "x", "description": "y" * 20001, "categories": []},
        {"subject": "x", "description": "", "categories": [{"id": "a", "name": "b"}] * 101},
    ],
)
def test_triage_rejects_bad_input(client, body) -> None:
    assert client.post("/triage", json=body).status_code == 422


def test_embedder_is_lazy_and_injectable() -> None:
    calls: list[int] = []

    class Counting(FakeEmbedder):
        def embed(self, texts):
            calls.append(len(texts))
            return super().embed(texts)

    client = TestClient(create_app(Counting()))
    client.post("/embed", json={"texts": ["a", "b"]})
    assert calls == [2]
