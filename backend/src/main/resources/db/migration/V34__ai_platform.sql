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

-- Chunks of PUBLISHED knowledge only (D2, D3); required_permission is what a caller needs to retrieve the chunk.
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
ALTER TABLE ai_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_chunks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- One row per tenant, created lazily (D9).
CREATE TABLE ai_settings (
    tenant_id      uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    enabled        boolean NOT NULL DEFAULT true,
    monthly_limit  int NOT NULL DEFAULT 500 CHECK (monthly_limit BETWEEN 1 AND 100000),
    updated_at     timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0
);
ALTER TABLE ai_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_settings
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Usage log (D10): no question or answer text, ever. Append-only.
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
ALTER TABLE ai_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_requests
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- V0_1's default privileges already grant SELECT, INSERT, UPDATE, DELETE on new tables to nexusops_app
-- (ai_chunks and ai_settings need all four); the usage log is append-only.
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_chunks, ai_settings TO nexusops_app;
GRANT SELECT, INSERT ON ai_requests TO nexusops_app;
REVOKE UPDATE, DELETE ON ai_requests FROM nexusops_app;
