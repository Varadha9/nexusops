"""Local ticket triage: keyword rules for priority, embedding similarity for category."""

import re
from typing import Literal

from app.embeddings import MODEL_LABEL, Embedder
from app.schemas import Category, TriageOut

CATEGORY_THRESHOLD = 0.30

# First match wins. Word-boundary regexes so "download" does not match "down".
Priority = Literal["LOW", "NORMAL", "HIGH", "URGENT"]

PRIORITY_RULES: list[tuple[Priority, re.Pattern[str]]] = [
    ("URGENT", re.compile(r"\b(outage|down|urgent|asap|critical|security|breach)\b")),
    ("HIGH", re.compile(r"\b(error|broken|cannot|can't|failed|not working)\b")),
    ("LOW", re.compile(r"\b(question|how do|how to|wondering)\b")),
]


def _priority(text: str) -> tuple[Priority, str | None]:
    for priority, pattern in PRIORITY_RULES:
        match = pattern.search(text)
        if match:
            return priority, match.group(1)
    return "NORMAL", None


def _category(embedder: Embedder, text: str, categories: list[Category]) -> Category | None:
    if not categories:
        return None
    vectors = embedder.embed([text] + [c.name for c in categories])
    scores = vectors[1:] @ vectors[0]
    best = int(scores.argmax())
    return categories[best] if float(scores[best]) >= CATEGORY_THRESHOLD else None


def triage(
    embedder: Embedder, subject: str, description: str, categories: list[Category]
) -> TriageOut:
    text = f"{subject} {description}".strip()
    priority, keyword = _priority(f"{subject} {description}".lower().replace("’", "'"))
    category = _category(embedder, text, categories)
    lead = f'Mentions "{keyword}"' if keyword else "No priority keywords"
    tail = f"closest category: {category.name}" if category else "no matching category"
    return TriageOut(
        category_id=category.id if category else None,
        priority=priority,
        reason=f"{lead}; {tail}.",
        model=MODEL_LABEL,
    )
