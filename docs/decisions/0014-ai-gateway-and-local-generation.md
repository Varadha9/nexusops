# ADR-0014: AI gateway, permission-aware retrieval and local extractive generation

- Status: Accepted
- Date: 2026-11-20
- Spec: docs/superpowers/specs/2026-11-20-ai-platform-core-design.md

## Context
Phase 12 brings AI into NexusOps. The blueprint asks for answers grounded in a workspace's own data, with citations,
respecting tenant isolation and permissions, audited, metered and switchable off (blueprint §6, §7, §19–22). The
constraint for this slice is that nothing leaves our own stack: no API key, no hosted model, no paid service. Phases
9–11 are still to come, so the slice covers two document sources (HelpDesk knowledge-base articles and HR policies) and
three features (ticket triage, reply drafts, a policy question assistant).

## Decision
1. **The gateway owns the data; `ai-service` is stateless.** All database access, retrieval included, happens in the
   Spring backend under the tenant's row-level-security session. `ai-service` never connects to the database; it
   receives only the text the gateway chose to send and returns embeddings, selected sentences or a triage result.
2. **Permission-aware retrieval.** Published articles and policies are chunked (about 800 characters, 100 overlap) and
   embedded into a pgvector table. Each chunk carries `tenant_id`, `source_type`, `source_id` and the permission needed
   to read the source. A chunk is a candidate only if the tenant matches (RLS) and the caller holds that permission
   (`helpdesk.article.read`; `hr.self.service` or `hr.policy.manage`); the top five by cosine distance after that
   filter form the context. Retrieval therefore never returns something the caller couldn't open themselves, and a
   disabled module hides its AI features too.
3. **Index consistency by deleting in the transaction.** Removing a source's chunks happens in the same transaction that
   unpublishes, archives or edits it, so a withdrawn document cannot be retrieved after that commit. Re-embedding a
   published source happens after commit on a small dedicated executor, because it calls `ai-service`; a failure is
   logged and never fails the user's publish. The source stays un-indexed until the next edit or **Rebuild index**.
   Sources are described by a small shared-kernel SPI (`KnowledgeSource`, `KnowledgeChanged`), so HelpDesk and HRMS
   don't depend on `ai`.
4. **Local extractive generation behind a provider interface.** Embeddings come from `BAAI/bge-small-en-v1.5` via
   fastembed, baked into the image. The "generator" selects, by embedding similarity, the sentences of the retrieved
   passages that answer the question (cosine ≥ 0.45, at most 4 in total and 2 per passage) and cites the passages they
   came from; triage picks the closest category (≥ 0.30) and a priority by keyword rules. Every response says
   `mode: "LOCAL"`. A hosted or local LLM (Claude, Ollama) can be added later as another provider behind the same
   interface without changing the gateway contract or the UI.
5. **Grounding and defence.** Passages are data, never instructions. Cited passage numbers are mapped to sources only
   within the retrieved set and anything else is dropped; triage output is validated (an unknown category becomes
   "none", priority must be one of the four).
6. **Governed by default.** A per-workspace opt-out and monthly request limit (UTC month), a per-user rate limit, a
   usage row and an audit event for every completed request. Question and answer text are never stored or audited. AI
   never changes data: triage is applied by an agent through the ordinary ticket update, a draft goes into the composer
   unsent, and all output is labelled "AI-generated — check before use".
7. **Failure is explicit.** If `ai-service` is unreachable the gateway answers 503 and records nothing.

## Consequences
- Fully local and free to run, reproducible in CI, and the data never leaves the stack. The cost is a larger
  `ai-service` image (the model is baked in) and a slow first build.
- Extractive answers quote the documents: they can't hallucinate, but they are less fluent than an LLM's, can't combine
  facts across passages, and are only as good as the documents. The provider interface is the upgrade path.
- Postgres needs the `vector` extension. The dev image has been `pgvector/pgvector:pg17` since the first phase, so only
  the extension is new: a fresh volume gets it from the new init script `02-extensions.sh`, and existing volumes need
  `CREATE EXTENSION IF NOT EXISTS vector` once (a one-off command in the README).
- Documents published before the upgrade are not indexed until an admin clicks **Rebuild index** (Settings → AI); no
  backfill runs on upgrade.
- Editing a document makes it briefly unsearchable until its re-embedding completes; the index is rebuildable from the
  source tables at any time.
- Evaluation harnesses, document file ingestion, streaming, chat history, forecasting and workflow proposals are later
  Phase 12 parts.
