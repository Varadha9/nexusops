"""Local extractive generation: answer with the passage sentences most similar to the question."""

from typing import Literal

from app.embeddings import MODEL_LABEL, Embedder
from app.schemas import AnswerOut, Passage
from app.text import split_sentences

THRESHOLD = 0.45
MAX_SENTENCES = 4
MAX_PER_PASSAGE = 2

NOTHING_FOUND = "I couldn't find anything about that in your workspace's documents."
NO_CLEAR_ANSWER = "I couldn't find a clear answer in your workspace's documents. These may help:"
REPLY_PREFIX = "Hi,\n\nThanks for getting in touch. "
REPLY_SUFFIX = (
    "\n\nIf that doesn't solve it, reply to this message and we'll look into it further."
    "\n\nBest regards"
)
PROMPT_VERSIONS = {"TICKET_REPLY": "reply-v1", "POLICY_QUESTION": "policy-v1"}


def select(
    embedder: Embedder, question: str, passages: list[Passage]
) -> tuple[list[tuple[int, int, str]], dict[int, float]]:
    candidates = [(p.n, i, s) for p in passages for i, s in enumerate(split_sentences(p.text))]
    if not candidates:
        return [], {}
    vectors = embedder.embed([question] + [s for _, _, s in candidates])
    scores = vectors[1:] @ vectors[0]
    best_per_passage: dict[int, float] = {}
    for (n, _, _), score in zip(candidates, scores, strict=True):
        best_per_passage[n] = max(best_per_passage.get(n, -1.0), float(score))
    ranked = sorted(zip(candidates, scores, strict=True), key=lambda c: -float(c[1]))
    chosen: list[tuple[int, int, str]] = []
    per_passage: dict[int, int] = {}
    for (n, i, s), score in ranked:
        if score < THRESHOLD or len(chosen) == MAX_SENTENCES:
            break
        if per_passage.get(n, 0) == MAX_PER_PASSAGE:
            continue
        per_passage[n] = per_passage.get(n, 0) + 1
        chosen.append((n, i, s))
    order = {p.n: k for k, p in enumerate(passages)}
    chosen.sort(key=lambda c: (order[c[0]], c[1]))
    return chosen, best_per_passage


def answer(
    embedder: Embedder,
    task: Literal["TICKET_REPLY", "POLICY_QUESTION"],
    question: str,
    passages: list[Passage],
) -> AnswerOut:
    chosen, best_per_passage = select(embedder, question, passages)
    if chosen:
        cited = sorted({n for n, _, _ in chosen})
        if task == "TICKET_REPLY":
            # spec D6: the draft text carries no passage markers; the citations are still returned
            body = REPLY_PREFIX + " ".join(s for _, _, s in chosen) + REPLY_SUFFIX
        else:
            body = " ".join(f"{s} [{n}]" for n, _, s in chosen)
        return _out(body, cited, task, grounded=True)
    if best_per_passage:
        titles = {p.n: p.title for p in reversed(passages)}
        top = sorted(best_per_passage, key=lambda n: -best_per_passage[n])[:2]
        # Not a reply: a reply draft gets the bare listing, without the greeting or markers, so the
        # caller can show it instead of putting it in the reply box.
        marker = (lambda n: "") if task == "TICKET_REPLY" else (lambda n: f" [{n}]")
        listing = "\n".join(f"- {titles[n]}{marker(n)}" for n in top)
        return _out(f"{NO_CLEAR_ANSWER}\n{listing}", sorted(top), task, grounded=False)
    return _out(NOTHING_FOUND, [], task, grounded=False)


def _out(text: str, cited: list[int], task: str, *, grounded: bool) -> AnswerOut:
    return AnswerOut(
        answer=text,
        cited_passages=cited,
        grounded=grounded,
        model=MODEL_LABEL,
        prompt_version=PROMPT_VERSIONS[task],
    )
