import re

_BOUNDARY = re.compile(r"(?<=[.!?])\s+|\n\s*\n")


def split_sentences(text: str) -> list[str]:
    """Split on sentence punctuation followed by whitespace, and on blank lines."""
    return [piece.strip() for piece in _BOUNDARY.split(text) if piece and piece.strip()]
