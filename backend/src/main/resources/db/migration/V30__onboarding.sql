-- HRMS onboarding (D10): checklist templates, and the tasks an employee's onboarding created.
CREATE TABLE onboarding_templates (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    name_key     text NOT NULL,
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE onboarding_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON onboarding_templates
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE onboarding_template_items (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_id      uuid NOT NULL,
    position         integer NOT NULL CHECK (position >= 0),
    title            text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    description      text CHECK (description IS NULL OR length(description) <= 2000),
    owner            text NOT NULL CHECK (owner IN ('HR', 'MANAGER', 'EMPLOYEE')),
    due_offset_days  integer NOT NULL CHECK (due_offset_days BETWEEN 0 AND 365),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, template_id, position),
    FOREIGN KEY (tenant_id, template_id) REFERENCES onboarding_templates (tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE onboarding_template_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_template_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON onboarding_template_items
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE employee_onboardings (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id    uuid NOT NULL,
    template_id    uuid NOT NULL,
    template_name  text NOT NULL,
    started_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    started_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, employee_id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES onboarding_templates (tenant_id, id)
);
ALTER TABLE employee_onboardings ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_onboardings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_onboardings
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE employee_onboarding_tasks (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    onboarding_id  uuid NOT NULL,
    task_id        uuid NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, onboarding_id, task_id),
    FOREIGN KEY (tenant_id, onboarding_id) REFERENCES employee_onboardings (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES tasks (id)
);
ALTER TABLE employee_onboarding_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_onboarding_tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_onboarding_tasks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Existing workspaces get the standard template (new ones get it from HrSetup).
DO $$
DECLARE
    t uuid;
    tpl uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        tpl := gen_random_uuid();
        INSERT INTO onboarding_templates (id, tenant_id, name, name_key, created_at, updated_at)
        VALUES (tpl, t, 'Standard onboarding', 'standard onboarding', now(), now());
        INSERT INTO onboarding_template_items (id, tenant_id, template_id, position, title, owner, due_offset_days) VALUES
            (gen_random_uuid(), t, tpl, 0, 'Prepare laptop and accounts', 'HR', 0),
            (gen_random_uuid(), t, tpl, 1, 'Welcome meeting and team introductions', 'MANAGER', 0),
            (gen_random_uuid(), t, tpl, 2, 'Read the employee handbook', 'EMPLOYEE', 3),
            (gen_random_uuid(), t, tpl, 3, 'Complete required training', 'EMPLOYEE', 14),
            (gen_random_uuid(), t, tpl, 4, '30-day check-in', 'MANAGER', 30);
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
