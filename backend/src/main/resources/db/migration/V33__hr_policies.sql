-- HR policy library (D16). Titles weigh more than bodies in search (the policy-assistant baseline).
CREATE TABLE hr_policies (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title         text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 200),
    body          text NOT NULL CHECK (length(body) BETWEEN 1 AND 50000),
    status        text NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    author_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at  timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    search        tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', title), 'A') || setweight(to_tsvector('simple', body), 'B')) STORED,
    UNIQUE (tenant_id, id),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX hr_policies_status_idx ON hr_policies (tenant_id, status, updated_at);
CREATE INDEX hr_policies_search_idx ON hr_policies USING gin (search);
ALTER TABLE hr_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE hr_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hr_policies
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
