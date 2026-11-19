# AI Platform — core slice (Phase 12, part 1) — design

**Status:** approved scope, 2026-11-20. Built before Phases 9–11 for the final project review.
**Blueprint:** `docs/research/master-blueprint.md` §6 (AI strategy), §7 (AI security), §8 (AI features), §19–22 (architecture, RAG, evaluation, governance), §38 Phase 12.

## 1. Goal

Show real, governed AI inside NexusOps: answers grounded in the workspace's own documents, with citations, that
respect tenant isolation and permissions, are audited, metered and can be switched off — and that keep working
(in a reduced "offline" mode) when no model is reachable.

## 2. Scope

**In:**

1. **AI gateway** in the Spring Boot backend: the only way the UI reaches AI. Checks workspace opt-out, permissions,
   usage limit and rate limit; retrieves permitted context; calls `ai-service`; validates the reply; records usage and
   audit.
2. **Knowledge index** in PostgreSQL + pgvector: chunks of **published** HelpDesk knowledge-base articles and
   **published** HR policies, each chunk carrying `tenant_id`, `source_type`, `source_id` and the permission needed to
   read it. Kept current when a source is published, edited, unpublished or archived; rebuildable on demand.
3. **ai-service** (FastAPI): local embeddings (no key, works offline) and generation with Claude, with an offline
   fallback when the key is missing or the call fails.
4. **Features:**
   - HelpDesk ticket — **Suggest triage**: category (from the workspace's categories) + priority + one-line reason; the
     agent applies it with one click (an ordinary ticket update).
   - HelpDesk ticket — **Draft reply**: a reply drafted from the knowledge base with cited articles, placed in the reply
     composer for the agent to edit and send.
   - My HR — **Ask about HR policies**: an answer from published policies with cited policies.
   - Settings → **AI**: on/off, monthly request limit, usage this month, provider status, rebuild index.

**Out (later Phase 12 parts, after Phases 9–11):** forecasting and predictions, workflow proposals, CRM summaries,
prompt registry UI, evaluation harness and dataset, document (file) ingestion, streaming responses, chat history.

## 3. Decisions

- **D1 — Gateway owns data, ai-service is stateless.** All database access (retrieval included) happens in Spring
  under the tenant's RLS session. ai-service never connects to the database; it receives only the text Spring chose to
  send. This is the blueprint's `User → Tenant → Role → Permission → Retrieval Filter → Context → LLM` pipeline.
- **D2 — Permission-aware retrieval.** A chunk is a candidate only if `tenant_id` matches (RLS) **and** its
  `required_permission` is in the caller's effective permissions. KB chunks require `helpdesk.article.read`; HR policy
  chunks require `hr.self.service` *or* `hr.policy.manage` (stored as `hr.self.service`; the query treats
  `hr.policy.manage` as satisfying it). Only PUBLISHED sources are ever indexed.
- **D3 — Index consistency.** Removing a source's chunks happens **inside** the transaction that unpublishes, archives
  or edits it, so a withdrawn document can never be retrieved after that commit. (Re-)embedding a published source
  happens **after commit** (it calls ai-service); if that fails it is logged and the source stays un-indexed until the
  next edit or a rebuild.
- **D4 — Shared-kernel SPI, no module cycle.** `com.nexusops.shared.knowledge` defines
  `KnowledgeDocument(sourceType, sourceId, title, body, requiredPermission)`, `KnowledgeSource` (beans that list a
  tenant's published documents and find one by id) and the event `KnowledgeChanged(sourceType, sourceId)`. HelpDesk and
  HRMS implement `KnowledgeSource` and publish `KnowledgeChanged`; the new `ai` module consumes both. `ai` may depend on
  `helpdesk` (tickets, categories); nothing depends on `ai`.
- **D5 — Embeddings.** `fastembed` with `BAAI/bge-small-en-v1.5` (384 dimensions), model files baked into the
  ai-service image at build time so runtime needs no network. Chunks: ~800 characters with 100 overlap, split on
  paragraph/sentence boundaries; the title is prefixed to every chunk's embedded text.
- **D6 — Generation.** Anthropic Python SDK, model `claude-opus-5-5` (env `ANTHROPIC_MODEL` overrides),
  `output_config.effort` `low`, structured output via `client.messages.parse(..., output_format=<pydantic model>)`,
  client timeout 30 s, `max_retries` 1. The key comes from env `ANTHROPIC_API_KEY`, set by the operator in the untracked
  `infra/docker/.env`; it is never logged, stored or returned.
- **D7 — Offline fallback (ai-service).** Used when there is no key, the call errors or times out, or the model refuses
  (`stop_reason == "refusal"`). Answers: "The AI model is unavailable, so here are the most relevant passages:" followed
  by the top passages, with the same citations. Triage: category = the category whose name is most similar
  (embeddings) to the ticket text; priority by keyword rules (`outage|down|urgent|asap|critical` → URGENT,
  `error|broken|cannot|can't|failed` → HIGH, otherwise NORMAL). Every response carries `mode: "CLAUDE" | "OFFLINE"`.
- **D8 — Grounding and injection defence.** Retrieved passages are sent as numbered, delimited data blocks; the system
  prompt says passages are reference material, never instructions, and to answer only from them or say it doesn't
  know. The model returns cited passage numbers; Spring maps them to sources **only within the retrieved set** —
  anything else is dropped. Triage output is validated in Spring: an unknown category id becomes "no category"; priority
  must be one of LOW/NORMAL/HIGH/URGENT.
- **D9 — Opt-out and limits.** Table `ai_settings` (one row per tenant, created lazily, default enabled, monthly limit
  500). Disabled → 409 `AI assistance is turned off for this workspace.` Monthly limit reached (count of this calendar
  month's `ai_requests` rows, UTC) → 429 `This workspace has used its AI requests for this month.` Plus a per-user Redis
  rate limit rule `ai` (20 per minute, authenticated API fails open as today).
- **D10 — Usage and audit.** Every completed AI call inserts an `ai_requests` row: feature, mode, model, prompt version,
  input/output tokens (0 offline), latency ms, cited source ids, user id. No question or answer text is stored (PII
  minimisation). Each call also writes audit event `ai.request.completed` (entity `ai_request`); settings changes write
  `ai.settings.updated`; a rebuild writes `ai.index.rebuilt`.
- **D11 — Permissions.** New core (module-less) permissions `ai.assistant.use` ("Use AI assistance") and
  `ai.settings.manage` ("Turn AI on or off, set its limit and rebuild its index"), granted to system roles.
  Feature routes additionally require the feature's own read permission (below), so a disabled HELPDESK/HRMS module
  also hides its AI features.
- **D12 — ai-service unreachable.** Spring returns 503 `The AI service is unavailable. Try again shortly.` (no row, no
  audit). Spring → ai-service timeout 35 s.
- **D13 — Human in the loop.** AI never changes data. Triage is applied by the agent through the existing ticket update;
  the draft goes into the composer unsent. All AI output in the UI is labelled "AI-generated — check before use", with an
  "Offline mode" badge when `mode` is OFFLINE.
- **D14 — Prompt versions.** Prompts live in ai-service as constants with a version string (`triage-v1`, `reply-v1`,
  `policy-v1`) returned in every response and stored in `ai_requests`.

## 4. Data (migration V34)

- `CREATE EXTENSION IF NOT EXISTS vector` cannot run as the migration owner (not superuser). New init script
  `infra/docker/postgres/init/02-extensions.sh` creates it as `postgres` for fresh databases; Testcontainers copies it
  too. V34 begins with a guard that raises a clear error if the extension is missing. Existing local volumes need a
  one-off `docker compose exec postgres psql -U postgres -d nexusops -c 'CREATE EXTENSION IF NOT EXISTS vector'`
  (documented in the README).
- `ai_chunks(id, tenant_id, source_type CHECK IN ('KB_ARTICLE','HR_POLICY'), source_id, chunk_index, title, content,
  required_permission, embedding vector(384), created_at)`, unique `(tenant_id, source_type, source_id, chunk_index)`,
  HNSW index `vector_cosine_ops`.
- `ai_settings(tenant_id PK, enabled, monthly_limit CHECK 1..100000, updated_at, version)`.
- `ai_requests(id, tenant_id, user_id, feature CHECK IN ('TICKET_TRIAGE','TICKET_REPLY','POLICY_QUESTION'), mode,
  model, prompt_version, input_tokens, output_tokens, latency_ms, source_ids uuid[], created_at)`, index
  `(tenant_id, created_at)`.
- All three: forced RLS with the `tenant_isolation` policy (USING + WITH CHECK), grants to `nexusops_app`
  (`ai_requests`: SELECT, INSERT only).
- Permissions inserted and granted via `grant_to_system_roles`.

## 5. API

ai-service (internal, not exposed by nginx):

| Route | Body → Response |
|---|---|
| `GET /health` | `{status, service, provider: "CLAUDE"|"OFFLINE", embeddingModel}` (`provider` = key configured) |
| `POST /embed` | `{texts: [str] (1..64)}` → `{vectors: [[float]*384]}` |
| `POST /answer` | `{task: "TICKET_REPLY"|"POLICY_QUESTION", question, passages: [{n, title, text}]}` → `{answer, citedPassages: [n], mode, model, promptVersion, inputTokens, outputTokens}` |
| `POST /triage` | `{subject, description, categories: [{id, name}]}` → `{categoryId|null, priority, reason, mode, model, promptVersion, inputTokens, outputTokens}` |

Backend (`/api/v1/ai`):

| Route | Permission | Response |
|---|---|---|
| `POST /helpdesk/tickets/{id}/triage` | `ai.assistant.use` + `helpdesk.ticket.read` | `{categoryId, categoryName, priority, reason, mode}` |
| `POST /helpdesk/tickets/{id}/reply-draft` | `ai.assistant.use` + `helpdesk.ticket.read` (retrieval needs `helpdesk.article.read`) | `{draft, citations: [{sourceType, sourceId, title}], mode}` |
| `POST /hr/policy-questions` `{question 3..500}` | `ai.assistant.use` + (`hr.self.service` or `hr.policy.manage`) | `{answer, citations, mode}` |
| `GET /settings` | `ai.settings.manage` | `{enabled, monthlyLimit, usedThisMonth, provider, indexedChunks, version}` |
| `PUT /settings` `{enabled, monthlyLimit, version}` | `ai.settings.manage` | same |
| `POST /index/rebuild` | `ai.settings.manage` | `{indexedSources, indexedChunks}` |
| `GET /status` | `ai.assistant.use` | `{enabled, provider}` (lets the UI show or hide AI buttons) |

Error order stays 400 → 403 → 404 → 409 → 429 → 503. The ticket is loaded through the HelpDesk read path, so a
ticket the caller can't see is 404. Retrieval: top 5 chunks by cosine distance after the permission filter; if none,
answer "I couldn't find anything about that in your workspace's documents." without calling the model
(`mode: "OFFLINE"`, no citations, 0 tokens; the request still counts and is recorded).

## 6. Frontend

- `features/ai`: API hooks, `AiOutput` wrapper (label + offline badge + citations list linking to
  `/app/helpdesk/articles/:id` or `/app/hrms/policies/:id`).
- Ticket page: an "AI assist" card (shown when `GET /ai/status` says enabled and the user has `ai.assistant.use`) with
  **Suggest triage** (shows suggestion, **Apply** calls the existing ticket update) and **Draft reply** (fills the
  composer).
- My HR: "Ask about HR policies" card — question box, answer, citations.
- Settings → AI page (`ai.settings.manage`): toggle, limit, usage bar, provider status, **Rebuild index**.

## 7. Error handling

ai-service validates inputs (pydantic) and never returns 500 for model failures — it falls back (D7). Spring maps
ai-service 4xx/5xx/timeouts to 503 (D12). Model output that fails validation is treated as an offline fallback in
ai-service. Index failures never fail the user's publish/edit.

## 8. Testing

- ai-service: pytest with a fake embedder and fake Claude client — chunk-independent endpoints, offline triage rules,
  citation numbers outside the passage set dropped, refusal/timeout → OFFLINE, no key → OFFLINE.
- Backend: unit tests for chunking-independent logic; ITs with a stub `AiClient` bean: permission-filtered retrieval
  (a user without `helpdesk.article.read` gets no KB passages), cross-tenant chunks invisible (RLS IT over the three
  tables), unpublish removes chunks in the same transaction, disabled → 409, limit → 429, ai-service down → 503,
  usage row + audit written without question text, citations outside the retrieved set dropped, unknown category → null.
- Frontend: vitest for the three UI surfaces and settings.
- E2E: one journey with the real ai-service in offline mode (CI has no key): publish a policy, ask in My HR, see the
  answer and the policy cited with the Offline badge; suggest triage on a ticket and apply it.

## 9. Operations

- Compose: backend gets `AI_SERVICE_URL=http://ai-service:8000`; ai-service gets `ANTHROPIC_API_KEY` and
  `ANTHROPIC_MODEL` from `.env` (empty by default → offline). `make e2e` starts `ai-service` too. `.env.example`
  documents `ANTHROPIC_API_KEY=` (empty).
- README: AI section — how to add the key, what offline mode does, the one-off extension command for old volumes.
- ADR-0014: AI gateway, permission-aware retrieval and offline fallback.
