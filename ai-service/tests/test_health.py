from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import FakeEmbedder

client = TestClient(create_app(FakeEmbedder()))


def test_health_reports_ok() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "ai-service",
        "provider": "LOCAL",
        "embeddingModel": "bge-small-en-v1.5",
    }
