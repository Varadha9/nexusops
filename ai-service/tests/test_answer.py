from app.generation import answer
from app.schemas import Passage

NO_ANSWER = "I couldn't find a clear answer in your workspace's documents. These may help:"
NOTHING = "I couldn't find anything about that in your workspace's documents."


def test_no_passages(embedder) -> None:
    out = answer(embedder, "POLICY_QUESTION", "How many leave days?", [])
    assert out.answer == NOTHING
    assert out.cited_passages == []
    assert out.mode == "LOCAL"
    assert out.model == "bge-small-en-v1.5"
    assert out.prompt_version == "policy-v1"
    assert (out.input_tokens, out.output_tokens) == (0, 0)


def test_selects_relevant_sentences_with_markers(embedder) -> None:
    passages = [
        Passage(
            n=3,
            title="Leave",
            text="Employees get twenty annual leave days. The cafeteria opens at noon.",
        ),
        Passage(n=7, title="Holidays", text="Annual leave days carry over into next year."),
    ]
    out = answer(embedder, "POLICY_QUESTION", "How many annual leave days?", passages)
    assert out.answer == (
        "Employees get twenty annual leave days. [3] "
        "Annual leave days carry over into next year. [7]"
    )
    assert out.cited_passages == [3, 7]
    assert out.prompt_version == "policy-v1"


def test_passage_numbers_come_only_from_the_request(embedder) -> None:
    passages = [Passage(n=7, title="Leave", text="Annual leave days are twenty.")]
    out = answer(embedder, "POLICY_QUESTION", "annual leave days", passages)
    assert out.cited_passages == [7]
    assert out.answer.endswith("[7]")


def test_at_most_two_per_passage_and_four_total(embedder) -> None:
    text = " ".join(f"Leave days rule number{i} applies." for i in range(5))
    passages = [
        Passage(n=1, title="A", text=text),
        Passage(n=2, title="B", text=text),
        Passage(n=3, title="C", text=text),
    ]
    out = answer(embedder, "POLICY_QUESTION", "Leave days rule applies", passages)
    assert out.answer.count("[") == 4
    assert out.answer.count("[1]") <= 2
    assert out.answer.count("[2]") <= 2
    assert out.answer.count("[3]") <= 2


def test_original_order_within_a_passage(embedder) -> None:
    passages = [
        Passage(
            n=1,
            title="A",
            text="Refund requests need an invoice. Refund requests need approval from finance.",
        )
    ]
    out = answer(
        embedder, "POLICY_QUESTION", "Refund requests need approval from finance", passages
    )
    assert out.answer.index("an invoice") < out.answer.index("approval from finance")


def test_nothing_over_threshold_lists_top_two_titles(embedder) -> None:
    passages = [
        Passage(n=1, title="Alpha", text="Zebras graze on savannah grass."),
        Passage(n=2, title="Beta", text="Quarterly budgets close in March."),
        Passage(n=3, title="Gamma", text="Parking permits renew yearly."),
    ]
    out = answer(embedder, "POLICY_QUESTION", "quantum chromodynamics", passages)
    lines = out.answer.split("\n")
    assert lines[0] == NO_ANSWER
    assert len(lines) == 3
    assert len(out.cited_passages) == 2
    for line in lines[1:]:
        assert line.startswith("- ")
    assert out.cited_passages == sorted(out.cited_passages)


def test_nothing_over_threshold_ranks_by_best_sentence(embedder) -> None:
    passages = [
        Passage(n=1, title="Far", text="Zebras graze."),
        Passage(
            n=2, title="Near", text="Leave policy overview and many other unrelated words here."
        ),
        Passage(n=3, title="Mid", text="Leave rules too, plus lots of other padding words today."),
    ]
    out = answer(embedder, "POLICY_QUESTION", "leave", passages)
    assert out.answer.startswith(NO_ANSWER)
    assert "- Near [2]" in out.answer
    assert "- Mid [3]" in out.answer
    assert out.cited_passages == [2, 3]


def test_ticket_reply_wraps_sentences_without_markers(embedder) -> None:
    passages = [Passage(n=4, title="Reset", text="Reset your password from the login page.")]
    out = answer(embedder, "TICKET_REPLY", "Reset password", passages)
    assert out.answer == (
        "Hi,\n\nThanks for getting in touch. Reset your password from the login page.\n\n"
        "If that doesn't solve it, reply to this message and we'll look into it further."
        "\n\nBest regards"
    )
    assert out.cited_passages == [4]
    assert out.prompt_version == "reply-v1"


def test_ticket_reply_without_match_keeps_markers(embedder) -> None:
    passages = [Passage(n=1, title="Alpha", text="Zebras graze on savannah grass.")]
    out = answer(embedder, "TICKET_REPLY", "quantum chromodynamics", passages)
    assert out.answer == (
        "Hi,\n\nThanks for getting in touch. "
        f"{NO_ANSWER}\n- Alpha [1]\n\n"
        "If that doesn't solve it, reply to this message and we'll look into it further."
        "\n\nBest regards"
    )
    assert out.cited_passages == [1]
    assert out.prompt_version == "reply-v1"


def test_ticket_reply_with_no_passages(embedder) -> None:
    out = answer(embedder, "TICKET_REPLY", "anything", [])
    assert out.answer == NOTHING
    assert out.cited_passages == []
    assert out.prompt_version == "reply-v1"
