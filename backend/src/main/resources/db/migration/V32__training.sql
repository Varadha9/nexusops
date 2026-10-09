-- HRMS training (D9): courses that teach skills, and assignments of courses to employees.
CREATE TABLE courses (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title           text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 200),
    description     text CHECK (description IS NULL OR length(description) <= 5000),
    duration_hours  numeric(5, 1) CHECK (duration_hours IS NULL OR (duration_hours > 0 AND duration_hours <= 999)),
    link            text CHECK (link IS NULL OR (length(link) <= 500 AND link ~ '^https?://')),
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
ALTER TABLE courses ENABLE ROW LEVEL SECURITY;
ALTER TABLE courses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON courses
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE course_skills (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    course_id  uuid NOT NULL,
    skill_id   uuid NOT NULL,
    level      integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, course_id, skill_id),
    FOREIGN KEY (tenant_id, course_id) REFERENCES courses (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills (tenant_id, id)
);
CREATE INDEX course_skills_skill_idx ON course_skills (tenant_id, skill_id, level);
ALTER TABLE course_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE course_skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON course_skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE training_assignments (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    course_id        uuid NOT NULL,
    employee_id      uuid NOT NULL,
    due_on           date,
    status           text NOT NULL CHECK (status IN ('ASSIGNED', 'COMPLETED', 'CANCELLED')),
    assigned_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    assigned_at      timestamptz NOT NULL,
    completed_on     date,
    completion_note  text CHECK (completion_note IS NULL OR length(completion_note) <= 500),
    cancelled_at     timestamptz,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((status = 'COMPLETED') = (completed_on IS NOT NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, course_id) REFERENCES courses (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id)
);
CREATE UNIQUE INDEX training_assignments_open_uq ON training_assignments (tenant_id, course_id, employee_id)
    WHERE status = 'ASSIGNED';
CREATE INDEX training_assignments_employee_idx ON training_assignments (tenant_id, employee_id, status);
ALTER TABLE training_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_assignments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
