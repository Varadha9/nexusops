-- HRMS (Phase 8): module permissions (D20), skills (D7), departments (D5) and job roles with required skills (D6).
INSERT INTO permissions (code, module_code, description) VALUES
    ('hr.settings.manage', 'HRMS', 'Manage departments, job roles, skills, leave types, holidays and onboarding templates'),
    ('hr.leave.approve',   'HRMS', 'See all leave requests, request leave for others, approve, reject and cancel'),
    ('hr.training.manage', 'HRMS', 'Manage courses and assign, complete and cancel training'),
    ('hr.policy.manage',   'HRMS', 'Write, publish and archive HR policies'),
    ('hr.self.service',    'HRMS', 'See your own HR record, request leave and read published policies');
UPDATE permissions SET description = 'View employees, departments, job roles, skills and the HR dashboard'
WHERE code = 'hr.employee.read';
UPDATE permissions SET description = 'Hire, edit and end employment, set skills, start onboarding, adjust leave'
WHERE code = 'hr.employee.update';
SELECT grant_to_system_roles(ARRAY['hr.employee.read', 'hr.employee.update', 'hr.settings.manage',
                                   'hr.leave.approve', 'hr.training.manage', 'hr.policy.manage', 'hr.self.service']);

CREATE TABLE skills (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 60),
    name_key     text NOT NULL,
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE departments (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    name_key     text NOT NULL,
    description  text CHECK (description IS NULL OR length(description) <= 500),
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON departments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE job_roles (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title          text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 80),
    title_key      text NOT NULL,
    department_id  uuid,
    description    text CHECK (description IS NULL OR length(description) <= 2000),
    archived_at    timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, title_key),
    FOREIGN KEY (tenant_id, department_id) REFERENCES departments (tenant_id, id)
);
CREATE INDEX job_roles_department_idx ON job_roles (tenant_id, department_id);
ALTER TABLE job_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE job_role_skills (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    job_role_id    uuid NOT NULL,
    skill_id       uuid NOT NULL,
    minimum_level  integer NOT NULL CHECK (minimum_level BETWEEN 1 AND 5),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, job_role_id, skill_id),
    FOREIGN KEY (tenant_id, job_role_id) REFERENCES job_roles (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills (tenant_id, id)
);
ALTER TABLE job_role_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_role_skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_role_skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
