from app.text import split_sentences


def test_splits_on_sentence_punctuation() -> None:
    assert split_sentences("One. Two! Three? Four") == ["One.", "Two!", "Three?", "Four"]


def test_splits_on_blank_lines() -> None:
    assert split_sentences("First line\n\nSecond line") == ["First line", "Second line"]


def test_drops_empty_and_strips() -> None:
    assert split_sentences("  Hello.   \n\n\n  \n World.  ") == ["Hello.", "World."]


def test_empty_text() -> None:
    assert split_sentences("   ") == []


def test_does_not_split_inside_a_word_with_a_dot() -> None:
    assert split_sentences("Visit example.com today. Thanks.") == [
        "Visit example.com today.",
        "Thanks.",
    ]
