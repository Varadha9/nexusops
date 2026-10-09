-- HRMS leave (D11–D14): types, public holidays, requests (working days stored), and append-only balance adjustments.
CREATE TABLE leave_types (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name              text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 60),
    name_key          text NOT NULL,
    paid              boolean NOT NULL,
    annual_allowance  integer CHECK (annual_allowance IS NULL OR annual_allowance BETWEEN 0 AND 366),
    position          integer NOT NULL CHECK (position >= 0),
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE leave_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_types FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_types
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE holidays (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    day         date NOT NULL,
    name        text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    created_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, day)
);
ALTER TABLE holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE holidays FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON holidays
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE leave_requests (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id    uuid NOT NULL,
    type_id        uuid NOT NULL,
    start_date     date NOT NULL,
    end_date       date NOT NULL,
    days           integer NOT NULL CHECK (days BETWEEN 1 AND 366),
    reason         text CHECK (reason IS NULL OR length(reason) <= 500),
    status         text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    requested_at   timestamptz NOT NULL,
    decided_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_at     timestamptz,
    decision_note  text CHECK (decision_note IS NULL OR length(decision_note) <= 500),
    cancelled_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    cancelled_at   timestamptz,
    updated_at     timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (end_date >= start_date),
    CHECK (extract(year from start_date) = extract(year from end_date)),
    CHECK (status <> 'PENDING' OR decided_at IS NULL),
    CHECK (status NOT IN ('APPROVED', 'REJECTED') OR decided_at IS NOT NULL),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, type_id) REFERENCES leave_types (tenant_id, id)
);
CREATE INDEX leave_requests_employee_idx ON leave_requests (tenant_id, employee_id, start_date);
CREATE INDEX leave_requests_status_idx ON leave_requests (tenant_id, status, start_date);
ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_requests
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE leave_adjustments (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id  uuid NOT NULL,
    type_id      uuid NOT NULL,
    year         integer NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    days         numeric(5, 1) NOT NULL CHECK (days <> 0 AND days BETWEEN -366 AND 366 AND days * 2 = trunc(days * 2)),
    reason       text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 200),
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, type_id) REFERENCES leave_types (tenant_id, id)
);
CREATE INDEX leave_adjustments_employee_idx ON leave_adjustments (tenant_id, employee_id, year);
ALTER TABLE leave_adjustments ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_adjustments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_adjustments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- append-only, like stock_movements and ticket_messages
REVOKE UPDATE, DELETE ON leave_adjustments FROM nexusops_app;

-- Existing workspaces get the default leave types (new ones get them from HrSetup).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO leave_types (id, tenant_id, name, name_key, paid, annual_allowance, position, created_at, updated_at)
        VALUES (gen_random_uuid(), t, 'Annual leave', 'annual leave', true, 20, 0, now(), now()),
               (gen_random_uuid(), t, 'Sick leave', 'sick leave', true, 10, 1, now(), now()),
               (gen_random_uuid(), t, 'Unpaid leave', 'unpaid leave', false, NULL, 2, now(), now());
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
