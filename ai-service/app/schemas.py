from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, alias_generator=to_camel)


class HealthOut(CamelModel):
    status: str
    service: str
    provider: str
    embedding_model: str


class EmbedIn(CamelModel):
    texts: list[Annotated[str, Field(min_length=1, max_length=4000)]] = Field(
        min_length=1, max_length=64
    )


class EmbedOut(CamelModel):
    vectors: list[list[float]]


class Passage(CamelModel):
    n: int = Field(ge=1)
    title: str
    text: str


class AnswerIn(CamelModel):
    task: Literal["TICKET_REPLY", "POLICY_QUESTION"]
    question: str = Field(min_length=1, max_length=4000)
    passages: list[Passage] = Field(max_length=10)


class AnswerOut(CamelModel):
    answer: str
    cited_passages: list[int]
    mode: Literal["LOCAL"] = "LOCAL"
    model: str
    prompt_version: Literal["reply-v1", "policy-v1"]
    input_tokens: int = 0
    output_tokens: int = 0


class Category(CamelModel):
    id: str
    name: str


class TriageIn(CamelModel):
    subject: str = Field(min_length=1, max_length=300)
    description: str = Field(max_length=20000)
    categories: list[Category] = Field(max_length=100)


class TriageOut(CamelModel):
    category_id: str | None
    priority: Literal["LOW", "NORMAL", "HIGH", "URGENT"]
    reason: str
    mode: Literal["LOCAL"] = "LOCAL"
    model: str
    prompt_version: Literal["triage-v1"] = "triage-v1"
    input_tokens: int = 0
    output_tokens: int = 0
