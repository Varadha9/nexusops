-- HRMS employees (D2): the employment record of a directory person, and their skills (D7).
CREATE TABLE employees (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    person_id        uuid NOT NULL,
    employee_number  text NOT NULL CHECK (employee_number = btrim(employee_number)
                                          AND length(employee_number) BETWEEN 1 AND 40),
    number_key       text NOT NULL,
    department_id    uuid,
    job_role_id      uuid,
    manager_id       uuid,
    employment_type  text NOT NULL CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN')),
    start_date       date NOT NULL,
    end_date         date,
    end_reason       text CHECK (end_reason IS NULL OR length(end_reason) <= 500),
    user_id          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, person_id),
    UNIQUE (tenant_id, number_key),
    CHECK (end_date IS NULL OR end_date >= start_date),
    CHECK (end_date IS NOT NULL OR end_reason IS NULL),
    CHECK (manager_id IS NULL OR manager_id <> id),
    FOREIGN KEY (tenant_id, person_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES departments (tenant_id, id),
    FOREIGN KEY (tenant_id, job_role_id) REFERENCES job_roles (tenant_id, id),
    FOREIGN KEY (tenant_id, manager_id) REFERENCES employees (tenant_id, id)
);
CREATE UNIQUE INDEX employees_user_uq ON employees (tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX employees_department_idx ON employees (tenant_id, department_id);
CREATE INDEX employees_manager_idx ON employees (tenant_id, manager_id);
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employees
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE employee_skills (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id  uuid NOT NULL,
    skill_id     uuid NOT NULL,
    level        integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    note         text CHECK (note IS NULL OR length(note) <= 200),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, employee_id, skill_id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills (tenant_id, id)
);
ALTER TABLE employee_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
