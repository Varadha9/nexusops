# AI Platform Core (Phase 12, part 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Governed, cited AI assistance (ticket triage, reply drafts, an HR policy assistant) running entirely on our own stack — local embeddings, pgvector retrieval behind tenant RLS and permissions, extractive answers — with opt-out, limits, usage and audit.

**Architecture:** The Spring Boot `ai` module is the gateway: it checks opt-out, permissions, limits, retrieves permitted chunks from `ai_chunks` (pgvector, forced RLS), calls the stateless FastAPI `ai-service` for embeddings and extractive generation, validates citations against the retrieved set, and records usage + audit. HelpDesk and HRMS expose published documents through a shared-kernel `KnowledgeSource` SPI and announce changes with `KnowledgeChanged`; the index drops chunks inside the changing transaction and re-embeds after commit.

**Tech Stack:** Java 25 / Spring Boot 4.1 Modulith, PostgreSQL 17 + pgvector, Redis; Python 3.13 FastAPI + fastembed (`BAAI/bge-small-en-v1.5`, 384-d); React 19 + TS + TanStack Query; Playwright.

**Spec:** `docs/superpowers/specs/2026-11-20-ai-platform-core-design.md` (read it; it is binding — D1–D14).

## Global Constraints

- Branch `feat/ai-platform`. Commits carry **no** `Co-Authored-By`, AI or "Generated with Claude Code" trailer. Do not push.
- No API keys, no hosted model, no network at ai-service runtime. `mode` is always `"LOCAL"`, `model` always `"bge-small-en-v1.5"`.
- Migration: `V34__ai_platform.sql` (latest existing is V33).
- Permissions (core, `module_code` NULL), granted with `grant_to_system_roles`:
  - `ai.assistant.use` — `Use AI assistance`
  - `ai.settings.manage` — `Turn AI on or off, set its limit and rebuild its index`
- Source types: `KB_ARTICLE` (required permission `helpdesk.article.read`), `HR_POLICY` (required permission `hr.self.service`; a caller holding `hr.policy.manage` also satisfies it).
- Features: `TICKET_TRIAGE`, `TICKET_REPLY`, `POLICY_QUESTION`. Prompt/generator versions: `triage-v1`, `reply-v1`, `policy-v1`.
- Chunking: target 800 characters, 100 overlap, split on paragraph then sentence boundaries; embedded text = `title + "\n\n" + chunk`.
- Retrieval: top 5 by cosine distance after the permission filter.
- Extractive generation: sentence cosine ≥ 0.45, at most 4 sentences total, at most 2 per passage. Triage category threshold 0.30.
- Error order 400 → 403 → 404 → 409 → 429 → 503. Messages verbatim:
  - 409 `AI assistance is turned off for this workspace.`
  - 429 (monthly) `This workspace has used its AI requests for this month.`
  - 503 `The AI service is unavailable. Try again shortly.`
  - 400 field `question`: `Ask a question of 3 to 500 characters.`
  - 400 field `monthlyLimit`: `Enter a limit from 1 to 100000.`
  - No retrieved chunks: answer `I couldn't find anything about that in your workspace's documents.`
  - No sentence over threshold: `I couldn't find a clear answer in your workspace's documents. These may help:`
- Rate-limit rule `ai: { capacity: 20, window: 1m }`, per tenant+user, fails OPEN like the authenticated API.
- Audit actions: `ai.request.completed` (entity type `ai_request`), `ai.settings.updated` (`ai_settings`), `ai.index.rebuilt` (`ai_index`). Never store or audit question/answer text.
- Defaults: `ai_settings.enabled = true`, `monthly_limit = 500`. Month = UTC calendar month.
- Spring → ai-service: base URL property `nexusops.ai.service-url` (env `AI_SERVICE_URL`, default `http://localhost:8000`), connect timeout 2 s, read timeout 20 s.
- UI copy verbatim: card titles `AI assist`, `Ask about HR policies`; buttons `Suggest triage`, `Apply`, `Draft reply`, `Ask`, `Rebuild index`; label `AI-generated — check before use`; caption `Answered from your workspace's documents`; settings tab `AI`.
- Tests that compute "this month" must use UTC (`LocalDate.now(ZoneOffset.UTC)`).

## Review Focus

1. **A document withdrawn or edited must never be quoted afterwards** — unpublish/archive/edit deletes its chunks in the same transaction (Task 2 IT: publish → unpublish → search returns nothing, even if re-embedding never happens).
2. **A user without the source's permission gets nothing from it** — e.g. a custom role with `ai.assistant.use` + `helpdesk.ticket.read` but not `helpdesk.article.read` drafting a reply gets the "couldn't find" answer, no KB citations (Task 3 IT).
3. **Citations can only point at what was retrieved** — ai-service returns passage numbers; numbers outside 1..N are dropped in both ai-service (Task 1 test) and Spring (Task 3 IT with a fake returning `[1, 99]`).
4. **ai-service down or slow must not hang or 500** — Spring returns 503 within the read timeout and writes no usage row (Task 3 IT); a publish while ai-service is down still succeeds (Task 2 IT).
5. **Odd input** — empty/whitespace/601-char questions are 400 with the field message; ticket with empty description and no categories still triages (category null, priority from subject) (Tasks 1 and 3).

---

### Task 1: ai-service — local embeddings, extractive answers, triage

**Files:**
- Modify: `ai-service/pyproject.toml`, `ai-service/Dockerfile`, `ai-service/app/main.py`
- Create: `ai-service/app/schemas.py`, `ai-service/app/embeddings.py`, `ai-service/app/text.py`, `ai-service/app/generation.py`, `ai-service/app/triage.py`
- Test: `ai-service/tests/conftest.py`, `ai-service/tests/test_text.py`, `ai-service/tests/test_answer.py`, `ai-service/tests/test_triage.py`, `ai-service/tests/test_api.py` (keep `tests/test_health.py` passing — update its expected body)

**Interfaces (HTTP, consumed by Task 2/3's `HttpAiClient`; JSON is camelCase):**
- `GET /health` → `{"status":"ok","service":"ai-service","provider":"LOCAL","embeddingModel":"bge-small-en-v1.5"}`
- `POST /embed` `{"texts":[str]}` (1..64 items, each 1..4000 chars) → `{"vectors":[[float]*384]}` (L2-normalised)
- `POST /answer` `{"task":"TICKET_REPLY"|"POLICY_QUESTION","question":str(1..4000),"passages":[{"n":int>=1,"title":str,"text":str}] (0..10)}` → `{"answer":str,"citedPassages":[int],"mode":"LOCAL","model":"bge-small-en-v1.5","promptVersion":"reply-v1"|"policy-v1","inputTokens":0,"outputTokens":0}`
- `POST /triage` `{"subject":str(1..300),"description":str(0..20000),"categories":[{"id":str,"name":str}] (0..100)}` → `{"categoryId":str|null,"priority":"LOW"|"NORMAL"|"HIGH"|"URGENT","reason":str,"mode":"LOCAL","model":"bge-small-en-v1.5","promptVersion":"triage-v1","inputTokens":0,"outputTokens":0}`
- Invalid bodies → FastAPI 422.

- [ ] **Step 1: Dependencies.** In `pyproject.toml` add `"fastembed>=0.6"` and `"numpy>=2"` to `dependencies`. Keep dev deps. `pip install -e ".[dev]"` in a venv (`python3.13 -m venv .venv`).

- [ ] **Step 2: Schemas** (`app/schemas.py`) — pydantic v2 models with camelCase aliases (`model_config = ConfigDict(populate_by_name=True, alias_generator=to_camel)`) for every body above, with the length/size limits as `Field(min_length=..., max_length=...)` constraints and `Literal` types for task/priority.

- [ ] **Step 3: Embedder seam** (`app/embeddings.py`):

```python
from typing import Protocol
import numpy as np

MODEL_NAME = "BAAI/bge-small-en-v1.5"
MODEL_LABEL = "bge-small-en-v1.5"
DIMENSIONS = 384

class Embedder(Protocol):
    def embed(self, texts: list[str]) -> np.ndarray: ...  # shape (len(texts), 384), rows L2-normalised

class FastEmbedEmbedder:
    def __init__(self) -> None:
        from fastembed import TextEmbedding  # lazy: tests never load the model
        self._model = TextEmbedding(MODEL_NAME)

    def embed(self, texts: list[str]) -> np.ndarray:
        vectors = np.array(list(self._model.embed(texts)), dtype=np.float32)
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        return vectors / np.where(norms == 0, 1, norms)
```

- [ ] **Step 4: Write failing tests first** for `text.py`, `generation.py`, `triage.py` using a deterministic `FakeEmbedder` in `tests/conftest.py`: bag-of-words hashing into 384 dims (lower-cased word tokens, `hash` replaced by a stable `zlib.crc32(word) % 384`), L2-normalised — so texts sharing words are similar. Cases to cover:
  - `split_sentences`: splits on `.`/`!`/`?` + whitespace and on blank lines; keeps abbreviations-free simple text; drops empty/whitespace pieces; strips.
  - answer: picks only sentences ≥ 0.45 to the question; ≤ 4 total; ≤ 2 per passage; within a passage keeps original order; each sentence followed by ` [n]`; `citedPassages` = sorted unique passage numbers used; passage numbers come only from the request (a passage with `n=7` cites 7, never anything else).
  - no sentence over threshold → exactly `I couldn't find a clear answer in your workspace's documents. These may help:` + `\n- {title} [n]` for the top 2 passages by best-sentence similarity; cites those 2.
  - zero passages → `I couldn't find anything about that in your workspace's documents.` and `citedPassages == []`.
  - `TICKET_REPLY` wraps the sentences (markers removed) as `Hi,\n\nThanks for getting in touch. {sentences joined by " "}\n\nIf that doesn't solve it, reply to this message and we'll look into it further.\n\nBest regards`; still returns `citedPassages`; `promptVersion` `reply-v1`. With no sentence over threshold, the reply-draft body is the "may help" text instead of sentences (markers kept in that list).
  - triage priority rules on lower-cased `subject + " " + description`, first match wins: URGENT `outage|down|urgent|asap|critical|security|breach`; HIGH `error|broken|cannot|can't|failed|not working`; LOW `question|how do|how to|wondering`; else NORMAL. Use word-boundary regexes (`\bdown\b`, so "download" doesn't match).
  - triage category: most similar category name ≥ 0.30, else `None`; no categories → `None`.
  - reason: `Mentions "{keyword}"; closest category: {name}.` / `Mentions "{keyword}"; no matching category.` / `No priority keywords; closest category: {name}.` / `No priority keywords; no matching category.`

Run: `cd ai-service && .venv/bin/pytest -q` → FAIL (modules missing).

- [ ] **Step 5: Implement** `text.py` (`split_sentences(text) -> list[str]`), `generation.py` (`answer(embedder, task, question, passages) -> AnswerOut`), `triage.py` (`triage(embedder, subject, description, categories) -> TriageOut`). Core of the answer selection:

```python
THRESHOLD = 0.45
MAX_SENTENCES = 4
MAX_PER_PASSAGE = 2

def select(embedder, question, passages):
    candidates = [(p.n, i, s) for p in passages for i, s in enumerate(split_sentences(p.text))]
    if not candidates:
        return [], {}
    vectors = embedder.embed([question] + [s for _, _, s in candidates])
    scores = vectors[1:] @ vectors[0]
    best_per_passage: dict[int, float] = {}
    for (n, _, _), score in zip(candidates, scores):
        best_per_passage[n] = max(best_per_passage.get(n, -1.0), float(score))
    ranked = sorted(zip(candidates, scores), key=lambda c: -float(c[1]))
    chosen, per_passage = [], {}
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
```

- [ ] **Step 6: Routes** (`app/main.py`): `create_app(embedder: Embedder | None = None) -> FastAPI`; the embedder is created lazily on first use (`FastEmbedEmbedder()`) unless injected; `app = create_app()` at module level for uvicorn. Routes call the functions above; responses use `response_model_by_alias=True`. Tests use `create_app(FakeEmbedder())` with FastAPI's `TestClient`: health body, embed shape (384, normalised), 422 for empty `texts`, 65 texts, empty question, 11 passages, bad priority literal; answer and triage end-to-end.

- [ ] **Step 7: Run** `.venv/bin/pytest -q && .venv/bin/ruff check . && .venv/bin/ruff format --check .` (run `ruff format .` if needed) → all green.

- [ ] **Step 8: Bake the model into the image** (`Dockerfile`): set `ENV FASTEMBED_CACHE_PATH=/opt/models HF_HUB_OFFLINE=0` before install; after `pip install --no-cache-dir .` run `python -c "from fastembed import TextEmbedding; TextEmbedding('BAAI/bge-small-en-v1.5')"`, then `chmod -R a+rX /opt/models`, then the existing `pip uninstall` + `useradd` line; finally `ENV HF_HUB_OFFLINE=1`. Build and smoke-test:

```bash
docker build -t nexusops-ai-check ai-service
# --network none proves the model is baked in and nothing is downloaded at runtime
docker run --rm --network none nexusops-ai-check python -c "from app.main import create_app; from fastapi.testclient import TestClient; c=TestClient(create_app()); print(c.post('/embed', json={'texts':['hello']}).json()['vectors'][0][:3])"
```

It must print three floats. (`fastapi.testclient` needs `httpx` at runtime; if it is not installed in the image, use `python -c "from app.embeddings import FastEmbedEmbedder; print(FastEmbedEmbedder().embed(['hello'])[0][:3])"` instead.)

- [ ] **Step 9: Commit**

```bash
git add ai-service
git commit -m "feat(ai-service): local embeddings, extractive cited answers and ticket triage"
```

---

### Task 2: Knowledge index — pgvector schema, knowledge SPI, indexing

**Files:**
- Create: `infra/docker/postgres/init/02-extensions.sh`, `backend/src/main/resources/db/migration/V34__ai_platform.sql`
- Create: `backend/src/main/java/com/nexusops/shared/knowledge/{KnowledgeDocument,KnowledgeSource,KnowledgeChanged,package-info}.java` (package-info only if the `shared` module needs named-interface exposure — mirror how other `shared` subpackages are exposed)
- Create: `backend/src/main/java/com/nexusops/ai/{package-info,AiClient,AiUnavailableException,AiProperties,HttpAiClient,Chunker,KnowledgeIndex,RetrievedChunk}.java` and records `ai/client/{Passage,AiAnswer,AiTriage,CategoryOption,AiHealth}.java` (or inline in `AiClient` as nested records — pick one and keep it)
- Create: `backend/src/main/java/com/nexusops/helpdesk/ArticleKnowledge.java`, `backend/src/main/java/com/nexusops/hrms/PolicyKnowledge.java`
- Modify: `helpdesk/ArticleService.java` (publish `KnowledgeChanged("KB_ARTICLE", id)` in `update`, `publish`, `unpublish`, `archive`), `hrms/PolicyService.java` (same with `"HR_POLICY"`)
- Modify: `backend/src/test/java/com/nexusops/support/IntegrationTestSupport.java` (copy `02-extensions.sh` too), `support/TestBeans.java` (register `FakeAiClient` as `@Primary`)
- Create tests: `support/FakeAiClient.java`, `ai/ChunkerTest.java`, `ai/KnowledgeIndexIT.java`, `ai/AiRlsIT.java`

**Interfaces:**
- Produces (shared kernel):

```java
package com.nexusops.shared.knowledge;
public record KnowledgeDocument(String sourceType, UUID sourceId, String title, String body, String requiredPermission) {}
public interface KnowledgeSource {
    String sourceType();
    List<KnowledgeDocument> published();                 // current tenant, PUBLISHED only
    Optional<KnowledgeDocument> findPublished(UUID id);  // empty unless PUBLISHED
}
public record KnowledgeChanged(String sourceType, UUID sourceId) {}
```

- Produces (`com.nexusops.ai`, used by Task 3):

```java
public interface AiClient {
    List<float[]> embed(List<String> texts);                          // ≤64 per call; throws AiUnavailableException
    AiAnswer answer(String task, String question, List<Passage> passages);
    AiTriage triage(String subject, String description, List<CategoryOption> categories);
    AiHealth health();                                                // short timeout; throws AiUnavailableException
}
public record Passage(int n, String title, String text) {}
public record AiAnswer(String answer, List<Integer> citedPassages, String mode, String model, String promptVersion,
        int inputTokens, int outputTokens) {}
public record AiTriage(String categoryId, String priority, String reason, String mode, String model,
        String promptVersion, int inputTokens, int outputTokens) {}
public record CategoryOption(UUID id, String name) {}
public record AiHealth(String provider, String embeddingModel) {}
public class AiUnavailableException extends RuntimeException {}

public record RetrievedChunk(String sourceType, UUID sourceId, String title, String content, double distance) {}
@Service public class KnowledgeIndex {
    public List<RetrievedChunk> search(float[] query, Set<String> sourceTypes, Set<String> permissions, int limit);
    public RebuildResult rebuild();            // current tenant; returns counts
    public long chunkCount();                  // current tenant
    public record RebuildResult(int indexedSources, int indexedChunks) {}
}
public final class Chunker { public static List<String> chunks(String body); } // 800/100, paragraph→sentence
```

- [ ] **Step 1: Extension init script** — `02-extensions.sh` (executable, `set -euo pipefail`) runs `psql ... --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c 'CREATE EXTENSION IF NOT EXISTS vector'`. Add it to `IntegrationTestSupport` with another `.withCopyFileToContainer(... "02-extensions.sh" ...)`.

- [ ] **Step 2: Migration** `V34__ai_platform.sql`:

```sql
-- AI platform core (Phase 12 part 1): knowledge chunks (pgvector), per-tenant AI settings, usage log.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'The pgvector extension is missing. As a superuser run: CREATE EXTENSION IF NOT EXISTS vector';
    END IF;
END $$;

INSERT INTO permissions (code, module_code, description) VALUES
    ('ai.assistant.use',   NULL, 'Use AI assistance'),
    ('ai.settings.manage', NULL, 'Turn AI on or off, set its limit and rebuild its index');
SELECT grant_to_system_roles(ARRAY['ai.assistant.use', 'ai.settings.manage']);

CREATE TABLE ai_chunks (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    source_type         text NOT NULL CHECK (source_type IN ('KB_ARTICLE', 'HR_POLICY')),
    source_id           uuid NOT NULL,
    chunk_index         int  NOT NULL CHECK (chunk_index >= 0),
    title               text NOT NULL,
    content             text NOT NULL,
    required_permission text NOT NULL,
    embedding           vector(384) NOT NULL,
    created_at          timestamptz NOT NULL,
    UNIQUE (tenant_id, source_type, source_id, chunk_index)
);
CREATE INDEX ai_chunks_source_idx ON ai_chunks (tenant_id, source_type, source_id);
CREATE INDEX ai_chunks_embedding_idx ON ai_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE ai_settings (
    tenant_id      uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    enabled        boolean NOT NULL DEFAULT true,
    monthly_limit  int NOT NULL DEFAULT 500 CHECK (monthly_limit BETWEEN 1 AND 100000),
    updated_at     timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0
);

CREATE TABLE ai_requests (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id         uuid REFERENCES users (id) ON DELETE SET NULL,
    feature         text NOT NULL CHECK (feature IN ('TICKET_TRIAGE', 'TICKET_REPLY', 'POLICY_QUESTION')),
    mode            text NOT NULL,
    model           text NOT NULL,
    prompt_version  text NOT NULL,
    input_tokens    int NOT NULL DEFAULT 0,
    output_tokens   int NOT NULL DEFAULT 0,
    latency_ms      int NOT NULL,
    source_ids      uuid[] NOT NULL DEFAULT '{}',
    created_at      timestamptz NOT NULL
);
CREATE INDEX ai_requests_month_idx ON ai_requests (tenant_id, created_at);

-- forced RLS on all three (copy the exact tenant_isolation policy text from V33 for each table)
-- grants: mirror V33's GRANT lines; ai_chunks and ai_settings SELECT, INSERT, UPDATE, DELETE; ai_requests SELECT, INSERT only.
```

Write the RLS and GRANT statements out in full, copying the V33 pattern table by table.

- [ ] **Step 3: Knowledge SPI + publishers.** Add the three shared types. `ArticleKnowledge` (`@Component implements KnowledgeSource`, sourceType `KB_ARTICLE`, permission `HelpDeskPermissions.ARTICLE_READ`) and `PolicyKnowledge` (`HR_POLICY`, `hr.self.service`) read PUBLISHED rows through their module's repository (no permission checks — the caller is the indexer; RLS scopes to the tenant). In `ArticleService`/`PolicyService` inject `ApplicationEventPublisher` and publish `new KnowledgeChanged(type, id)` at the end of `update`, `publish`, `unpublish`, `archive` (inside the transaction).

- [ ] **Step 4: AiClient over HTTP.** `AiProperties` (`@ConfigurationProperties("nexusops.ai")`, field `serviceUrl` default `http://localhost:8000`); add `nexusops.ai.service-url: ${AI_SERVICE_URL:http://localhost:8000}` to `application.yml`. `HttpAiClient` uses Spring `RestClient` with `SimpleClientHttpRequestFactory`/JDK factory: connect 2 s, read 20 s (health: read 2 s via a second client). Any `RestClientException`/IO/timeout/non-2xx → `AiUnavailableException`. JSON field names exactly as Task 1's interface.

- [ ] **Step 5: FakeAiClient (test support)** — `@Primary` bean via `TestBeans`. Deterministic bag-of-words embedding identical in spirit to Task 1's fake (crc32 of lower-cased word % 384, L2-normalised). Public mutable knobs reset by `reset()`: `boolean down` (every method throws `AiUnavailableException`), `List<Integer> forcedCitations` (null = cite every passage), `String forcedCategoryId` (null = first category's id, or null if none), `AtomicInteger embedCalls`. `answer` returns `"FAKE: " + passages.size() + " passages"`, mode `LOCAL`, model `bge-small-en-v1.5`, promptVersion by task. `health` returns `LOCAL`/`bge-small-en-v1.5` unless down.

- [ ] **Step 6: Chunker (TDD).** `ChunkerTest`: short body → one chunk equal to the trimmed body; long body of many paragraphs → chunks ≤ 900 chars (800 target + finishing the sentence boundary is not allowed to exceed 900), consecutive chunks overlap by ≥ 1 sentence when splitting inside a paragraph; blank/whitespace body → empty list; a single 3000-char "sentence" with no punctuation is hard-split at 800 with 100 overlap. Then implement.

- [ ] **Step 7: KnowledgeIndex (TDD with `KnowledgeIndexIT`).**
  - `@EventListener onChanged(KnowledgeChanged e)` → `DELETE FROM ai_chunks WHERE source_type=? AND source_id=?` (runs inside the publisher's transaction; RLS scopes tenant).
  - `@TransactionalEventListener(phase = AFTER_COMMIT) reindex(KnowledgeChanged e)` → find the `KnowledgeSource` bean for `e.sourceType()`, `findPublished(id)`; if present, chunk, embed in batches of 32 (`title + "\n\n" + chunk`), and in a new transaction (`TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`) delete-then-insert its chunks. Catch `AiUnavailableException`/`RuntimeException` → `log.warn` (never rethrow).
  - Vectors are bound as text `'[v1,v2,...]'` with `?::vector`. Search SQL:

```sql
SELECT source_type, source_id, title, content, embedding <=> ?::vector AS distance
FROM ai_chunks
WHERE source_type = ANY(?) AND required_permission = ANY(?)
ORDER BY embedding <=> ?::vector
LIMIT ?
```

  - `rebuild()` in one transaction per call: delete the tenant's chunks, then for each source's `published()` embed + insert; returns counts.
  - IT cases (with HelpDesk + HRMS enabled via `TestHelpDesk.enable` / `TestHr.enable`, articles/policies created and published through the real API):
    1. publish article → chunks exist with `required_permission = 'helpdesk.article.read'`, `title` = article title.
    2. publish → unpublish → zero chunks for it; same for archive; edit of a published article replaces its chunks (count of rows for that source equals new chunk count, old content gone).
    3. With `FakeAiClient.down = true`, publishing still returns 200 and leaves zero chunks; after `down = false`, `rebuild()` (call the bean inside `TenantContext.callAs`) indexes it.
    4. `search` with permissions lacking `helpdesk.article.read` returns no KB chunks; with it, returns the article's chunk first for a query that shares its words.
    5. Draft (unpublished) policies are never indexed.

- [ ] **Step 8: AiRlsIT** — mirror `HrRlsIT`'s approach (insert as tenant A through the owner role helper with `app.tenant_id` set, read as tenant B via the app role) for `ai_chunks`, `ai_settings`, `ai_requests`: B sees none of A's rows, and an insert with A's tenant id while B is set fails the `WITH CHECK`.

- [ ] **Step 9: Run** `cd backend && ./gradlew test --tests '*ai*' --tests '*Article*' --tests '*Policy*' --tests '*ModularityTest*'` (or the repo's Modulith verification test name) then the full `./gradlew test`. All green.

- [ ] **Step 10: Commit** — `feat(ai): pgvector knowledge index fed by published articles and policies`

---

### Task 3: AI gateway — settings, usage, audit, and the three features

**Files:**
- Create: `backend/src/main/java/com/nexusops/ai/{AiPermissions,AiSettingsService,AiSettingsView,AiSettingsCommand,AiStatusView,AiRebuildView,AiCitation,AiTriageView,AiDraftView,AiAnswerView,PolicyQuestion,AiAssistService,AiUsage}.java`, `ai/web/AiController.java`
- Modify: `shared/ratelimit/RateLimits.java` (+ `RateLimitKeys.java`), `application.yml` (rule `ai`)
- Modify (if they enumerate routes/schemas and fail): `CrossTenantApiIT`, `OpenApiContractIT`
- Test: `ai/AiSettingsApiIT.java`, `ai/AiAssistApiIT.java`, `ai/AiUsageTest.java` (unit, if any pure logic is extracted)

**Interfaces:**
- Consumes: Task 2's `AiClient`, `KnowledgeIndex`, `RetrievedChunk`, `FakeAiClient`; `TicketService.get(UUID)` (404 when not visible), `CategoryService.list(false)` (`CategoryView` has `id`, `name`), `AuditService.record(AuditEntry)`, `CurrentAuthorities.codes()`, `TenantContext.requireTenantId()/userId()`.
- Produces (REST, used by Task 4):

| Route | Auth (`@PreAuthorize`) | Body → Response |
|---|---|---|
| `GET /api/v1/ai/status` | `hasAuthority('ai.assistant.use')` | `{enabled:boolean, provider:"LOCAL"|"UNAVAILABLE"}` |
| `GET /api/v1/ai/settings` | `hasAuthority('ai.settings.manage')` | `AiSettingsView{enabled, monthlyLimit, usedThisMonth, provider, indexedChunks, version}` |
| `PUT /api/v1/ai/settings` | same | `{enabled, monthlyLimit, version}` → `AiSettingsView` (stale version → 409 like other settings) |
| `POST /api/v1/ai/index/rebuild` | same | → `{indexedSources, indexedChunks}` |
| `POST /api/v1/ai/helpdesk/tickets/{id}/triage` | `hasAuthority('ai.assistant.use') and hasAuthority('helpdesk.ticket.read')` | → `{categoryId, categoryName, priority, reason, mode}` |
| `POST /api/v1/ai/helpdesk/tickets/{id}/reply-draft` | same | → `{draft, citations:[{sourceType, sourceId, title}], mode}` |
| `POST /api/v1/ai/hr/policy-questions` | `hasAuthority('ai.assistant.use') and hasAnyAuthority('hr.self.service','hr.policy.manage')` | `{question}` → `{answer, citations, mode}` |

- [ ] **Step 1: Rate limit.** Add `RateLimitKeys.user(String rule, UUID tenantId, UUID userId)` and `RateLimits.checkUser(String rule, UUID tenantId, UUID userId)` that fails OPEN on `RateLimiterUnavailableException` (log warn) and throws the existing `RateLimitExceeded` when denied. Add rule `ai: { capacity: 20, window: 1m }`.

- [ ] **Step 2: Failing ITs** (`AiAssistApiIT`, `AiSettingsApiIT`) — owner workspace with HelpDesk + HRMS enabled; `FakeAiClient.reset()` in `@BeforeEach`. Cases:
  - policy question: published policy "Remote work policy" → 200, `answer` starts with `FAKE:`, citations contain `{sourceType:"HR_POLICY", sourceId:<policy>, title:"Remote work policy"}`, `mode:"LOCAL"`.
  - citations outside the retrieved set dropped: `forcedCitations = [1, 99]` → exactly one citation.
  - nothing retrieved (no published policies) → answer `I couldn't find anything about that in your workspace's documents.`, `citations: []`, and a usage row still written; AiClient `answer` not called (assert via a counter on the fake).
  - `question` `"  "`, `"ab"`, 501 chars → 400 field `question` message verbatim; missing body → 400.
  - custom role (via `TestRoles.create`) with `ai.assistant.use` + `helpdesk.ticket.read` but not `helpdesk.article.read`: reply-draft → 200 with `citations: []` and the "couldn't find anything" draft; without `ai.assistant.use` → 403.
  - triage: categories "Billing", "Login"; ticket "Invoice charged twice" → `categoryId` = fake's forced id, `categoryName` "Billing"; `forcedCategoryId` = random UUID → `categoryId` null, `categoryName` null; priority passes through; ticket in another tenant / random id → 404.
  - disabled (`PUT settings enabled=false`) → each feature 409 verbatim; status shows `enabled:false`.
  - limit: `monthlyLimit=1`, first call 200, second 429 verbatim.
  - `FakeAiClient.down = true` → 503 verbatim, no new `ai_requests` row; `GET status` → `provider:"UNAVAILABLE"`.
  - usage + audit: after a policy question, one `ai_requests` row (feature `POLICY_QUESTION`, `source_ids` = cited policy ids, `user_id` = caller) and one audit event `ai.request.completed`; neither the row nor the audit details contain the question text (assert the question string is absent from the audit `details` JSON).
  - settings: GET defaults `enabled:true, monthlyLimit:500, usedThisMonth:0`; PUT `monthlyLimit` 0 and 100001 → 400 field message; stale version → 409; audit `ai.settings.updated`; rebuild returns counts and audits `ai.index.rebuilt`; member without `ai.settings.manage` → 403.

- [ ] **Step 3: Implement.**
  - `AiSettingsService`: `current()` (read or lazily insert defaults under `TenantLocks.lock("ai-settings")`), `update(AiSettingsCommand, version)`, `usedThisMonth()` = `count(*) FROM ai_requests WHERE created_at >= date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'`.
  - `AiAssistService` — one private pipeline used by all three features:
    1. validate input (400); 2. load ticket (404) where relevant; 3. `settings.enabled` else 409; 4. `usedThisMonth >= monthlyLimit` → 429 (`ApiProblem` with status 429 — add `ApiProblem.tooManyRequests(String)` if none exists, mirroring the others); 5. `rateLimits.checkUser("ai", tenant, user)`; 6. outside any transaction: embed the query (`AiClient.embed(List.of(q))`), `KnowledgeIndex.search(vector, types, effectivePermissions, 5)`, build `Passage(n=i+1, title, content)`; if none (answer features) → the no-match answer without calling `answer`; else call `answer`/`triage`; `AiUnavailableException` → 503; 7. map `citedPassages` to `AiCitation`s using only `1..passages.size()`, de-duplicated by `sourceId`, in passage order; 8. in a transaction (`TransactionTemplate`): insert `ai_requests`, `audit.record(ai.request.completed, entity ai_request, details {feature, mode, model, promptVersion, sourceIds, latencyMs})`.
  - Effective permissions for retrieval = `CurrentAuthorities.codes()`, plus `hr.self.service` when the caller holds `hr.policy.manage`.
  - Reply-draft query = `subject + "\n" + description`; source types `{KB_ARTICLE}`. Policy question: `{HR_POLICY}`. Triage: no retrieval; categories = `CategoryService.list(false)`; validate returned `categoryId` against them (unknown → null); `priority` must parse to `Priority` (else `NORMAL`).
  - `AiController` thin, mirrors `PolicyController` style; `GET status` calls `AiClient.health()` and maps failure to `UNAVAILABLE` (status never 503s).

- [ ] **Step 4: Run** the new ITs, then the full backend suite; fix `OpenApiContractIT` / route-count tests if they enumerate endpoints (add the new routes deliberately, don't loosen assertions).

- [ ] **Step 5: Commit** — `feat(ai): AI gateway with opt-out, limits, usage, audit, triage, reply drafts and the policy assistant`

---

### Task 4: Frontend — AI assist on tickets, policy assistant, Settings → AI

**Files:**
- Modify: `frontend/src/lib/api/types.ts` (AI types), `features/auth/permissions.tsx` (`aiUse: 'ai.assistant.use'`, `aiSettings: 'ai.settings.manage'`), `features/shell/nav.ts` (`SETTINGS_TABS` entry `{ to: '/app/settings/ai', label: 'AI', anyOf: [PERMISSIONS.aiSettings] }`), `features/shell/routes.tsx` (route wrapped in `RequirePermission anyOf={[PERMISSIONS.aiSettings]}`), `features/helpdesk/TicketDetailPage.tsx` (render `TicketAiCard`, pass `setDraft`), `features/hrms/MyHrPage.tsx` (render `PolicyAssistantCard`)
- Create: `features/ai/api.ts` (query/mutation hooks), `features/ai/AiOutput.tsx`, `features/ai/TicketAiCard.tsx`, `features/ai/PolicyAssistantCard.tsx`, `features/settings/AiSettingsPage.tsx`
- Test: `features/ai/TicketAiCard.test.tsx`, `features/ai/PolicyAssistantCard.test.tsx`, `features/settings/AiSettingsPage.test.tsx`; add fixtures beside existing ones

**Interfaces:**
- Consumes: Task 3's REST routes/shapes; `useApi`, `useCan`, `problemMessage`, existing ticket update call used by `TicketActions`/`TicketFormDialog` (reuse it for **Apply**: send the ticket's current fields with `categoryId`/`priority` replaced and `version`), `invalidateHelpDesk`.
- Produces: `TicketAiCard({ ticket, onDraft })`, `PolicyAssistantCard()`, `AiSettingsPage`.

- [ ] **Step 1: Failing tests** (follow `renderApp` + `fakeServer` patterns in existing tests; register refetch GETs after the action button appears):
  - `TicketAiCard`: hidden when `GET /api/v1/ai/status` → `enabled:false` or the user lacks `ai.assistant.use`; **Suggest triage** shows category, priority, reason and `AI-generated — check before use`; **Apply** sends the update with the suggested `categoryId` and `priority` and the ticket `version`, then refetches the ticket; a null category shows "No matching category" and Apply sends only the priority change; **Draft reply** calls `onDraft(draft)` and lists cited articles as links to `/app/helpdesk/articles/:id`; a 409/429/503 shows the server message.
  - `PolicyAssistantCard`: question < 3 chars shows the field message without calling the server; **Ask** shows the answer, `Answered from your workspace's documents`, and cited policies linking to `/app/hrms/policies/:id`; hidden when status disabled.
  - `AiSettingsPage`: shows enabled toggle, limit, `usedThisMonth of monthlyLimit`, provider, indexed chunks; saving sends `version`; 400 field error shown under the limit; **Rebuild index** shows `Indexed {n} documents ({m} passages).`
- [ ] **Step 2: Implement** the components with existing UI primitives (`Card`, `Button`, `Textarea`, `Field`, `FormError`, `toast`). `AiOutput` renders the label, caption, body (preserve line breaks) and the citation list. Query keys under `['ai', ...]`; settings mutations invalidate `['ai']`.
- [ ] **Step 3: Run** `cd frontend && npm run format:check && npm run lint && npm run typecheck && npx vitest run` — all green (format with `npm run format` if needed).
- [ ] **Step 4: Commit** — `feat(ai-ui): AI assist on tickets, the HR policy assistant and Settings → AI`

---

### Task 5: Compose, E2E journey, README and ADR

**Files:**
- Modify: `infra/docker/docker-compose.yml` (backend env `AI_SERVICE_URL: http://ai-service:8000`; backend `depends_on` adds `ai-service`), `Makefile` (`e2e` target starts `ai-service` too), `.github/workflows/*.yml` only if the e2e job lists services explicitly
- Create: `frontend/e2e/ai.spec.ts`, `docs/decisions/0014-ai-gateway-and-local-generation.md`
- Modify: `README.md` (AI section + spec line in the E2E list)

- [ ] **Step 1: Existing DB volume.** For the local stack only: `docker compose -f infra/docker/docker-compose.yml exec postgres psql -U postgres -d nexusops -c 'CREATE EXTENSION IF NOT EXISTS vector'` before bringing the new backend up.
- [ ] **Step 2: E2E** `ai.spec.ts` — reuse helpers from `e2e/support` and the patterns in `hrms.spec.ts`/`helpdesk.spec.ts`: new workspace; enable HelpDesk + HRMS; create a ticket category "Billing"; publish a KB article "Refund requests" whose body explains refunds are processed within 5 working days; publish an HR policy "Remote work" whose body says employees may work remotely up to 2 days a week with manager approval; open a ticket "Customer charged twice — wants a refund"; **Suggest triage** → shows `Billing`, **Apply** → ticket shows category Billing; **Draft reply** → composer contains `refund`; My HR → **Ask** "How many days can I work remotely?" → answer contains `2 days` and a link "Remote work"; Settings → AI shows usage `3`.
- [ ] **Step 3: Run the stack and journeys** with local Chrome through a TEMPORARY `frontend/playwright.chrome.config.ts` (extends `playwright.config.ts`, project `use: { ...devices['Desktop Chrome'], channel: 'chrome' }`), deleted before committing:

```bash
export REFRESH_RATE_LIMIT=600 && docker compose -f infra/docker/docker-compose.yml --profile app up -d --build --wait postgres redis mailpit ai-service backend frontend
cd frontend && npx playwright test -c playwright.chrome.config.ts
```

  All journeys (existing + `ai.spec.ts`) pass. Restart `frontend` if requests 502 after the backend is recreated.
- [ ] **Step 4: README** — "AI assistance (Phase 12, part 1)": what it does; everything runs locally (no key, no paid service); how answers are assembled and cited; permissions and opt-out; the one-off pgvector command for existing volumes. **ADR-0014**: context, decision (gateway owns data; permission-aware retrieval; delete-in-transaction index consistency; local extractive generation behind a provider interface), consequences.
- [ ] **Step 5: Commit** — `test(e2e): AI triage, reply draft and the policy assistant; README and ADR-0014`
