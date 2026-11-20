import pytest

from app.schemas import Category
from app.triage import triage

CATEGORIES = [Category(id="c-bill", name="Billing invoice"), Category(id="c-net", name="Network")]


@pytest.mark.parametrize(
    ("subject", "priority", "keyword"),
    [
        ("Total outage in the office", "URGENT", "outage"),
        ("Site is down", "URGENT", "down"),
        ("Security review", "URGENT", "security"),
        ("Printer error", "HIGH", "error"),
        ("I can't log in", "HIGH", "can't"),
        ("Export not working", "HIGH", "not working"),
        ("Quick question", "LOW", "question"),
        ("How to change theme", "LOW", "how to"),
        ("Please update my address", "NORMAL", None),
    ],
)
def test_priority_rules(embedder, subject, priority, keyword) -> None:
    out = triage(embedder, subject, "", [])
    assert out.priority == priority
    if keyword:
        assert out.reason == f'Mentions "{keyword}"; no matching category.'
    else:
        assert out.reason == "No priority keywords; no matching category."


def test_word_boundaries(embedder) -> None:
    assert triage(embedder, "Download the report", "", []).priority == "NORMAL"
    assert triage(embedder, "Shutdown notice", "", []).priority == "NORMAL"


def test_first_match_wins(embedder) -> None:
    out = triage(embedder, "Question about an outage", "", [])
    assert out.priority == "URGENT"


def test_uses_description_too(embedder) -> None:
    assert triage(embedder, "Help", "the export failed", []).priority == "HIGH"


def test_category_match(embedder) -> None:
    out = triage(embedder, "Billing invoice is wrong", "", CATEGORIES)
    assert out.category_id == "c-bill"
    assert out.reason == "No priority keywords; closest category: Billing invoice."


def test_category_match_with_keyword(embedder) -> None:
    out = triage(embedder, "Network down", "", CATEGORIES)
    assert out.category_id == "c-net"
    assert out.reason == 'Mentions "down"; closest category: Network.'


def test_below_threshold_is_no_category(embedder) -> None:
    out = triage(embedder, "Zebras graze", "", CATEGORIES)
    assert out.category_id is None
    assert out.reason == "No priority keywords; no matching category."


def test_metadata(embedder) -> None:
    out = triage(embedder, "Hello", "", CATEGORIES)
    assert out.mode == "LOCAL"
    assert out.model == "bge-small-en-v1.5"
    assert out.prompt_version == "triage-v1"
    assert (out.input_tokens, out.output_tokens) == (0, 0)
