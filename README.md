# NexusOps

Multi-tenant business operations platform (capstone → MVP). One tenant, one business context,
one permission model, one operational event stream — with modular business capabilities on top.

- Blueprint: `docs/research/master-blueprint.md`
- Current design: `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`
- Canonical data model (Phase 4): `docs/superpowers/specs/2026-10-06-canonical-data-model-design.md`
- CRM (Phase 5): `docs/superpowers/specs/2026-10-07-crm-mvp-design.md`
- Inventory (Phase 6): `docs/superpowers/specs/2026-10-08-inventory-mvp-design.md`
- HelpDesk (Phase 7): `docs/superpowers/specs/2026-10-09-helpdesk-mvp-design.md`
- HRMS (Phase 8): `docs/superpowers/specs/2026-10-09-hrms-mvp-design.md`
- AI platform core (Phase 12, part 1): `docs/superpowers/specs/2026-11-20-ai-platform-core-design.md`
- Decisions: `docs/decisions/`
- Implementation plans: `docs/superpowers/plans/`

## Prerequisites
JDK 25, Node 22, Python 3.13, Docker.

## Quick start
```bash
make up        # Postgres :5433, Redis :6380, Mailpit UI http://localhost:8025
make backend   # API http://localhost:8081 (Swagger UI: /swagger-ui.html)
make frontend  # UI  http://localhost:5173 (proxies /api to :8081)
```
Or run everything in containers with `make up-all` (UI http://localhost:3000, API http://localhost:8081).

One-time ai-service setup: `cd ai-service && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`.

| Service | Host port |
|---|---|
| Postgres | 5433 |
| Redis | 6380 |
| Mailpit SMTP / UI | 1025 / 8025 |
| Backend API | 8081 |
| Frontend (dev / container) | 5173 / 3000 |
| ai-service | 8000 |

Host ports can be overridden through `infra/docker/.env` (see `.env.example`).

## Using the app
- Run `make up && make backend` in one terminal and `make frontend` in another, then open http://localhost:5173/signup
  to create a workspace. The verification email arrives in Mailpit (http://localhost:8025).
- The staff console is at http://localhost:5173/platform/login. Create an operator first with
  `make platform-admin EMAIL=you@example.com`.

## Records (Phase 4)
Shared by every module, in the app under **Directory**, **Products** and **Tasks**:
- **Directory:** people and organizations, one record each. A probable duplicate is refused until you give a reason
  (ADR-0008). A record plays roles (customer, supplier, employee); employee details need the employee permissions.
- **Products:** goods and services with unique SKUs and list prices.
- **Activity, tasks and documents:** attached to any person, organization or product. Documents are up to 10 MB each,
  with a per-plan storage quota, and are always downloaded, never opened in the browser (ADR-0009).
- Records are archived, never deleted. Every change is in the audit log.

## CRM (Phase 5)
Switch the module on under **Settings → Modules**; every CRM permission switches off with it (ADR-0010).
- **Leads:** prospects as you heard of them, entered by hand or imported from a CSV file (up to 500 rows; all or
  nothing, with per-row errors). New → Contacted → Qualified, or Disqualified with a reason.
- **Conversion:** links or creates the canonical person and organization (the directory's duplicate check applies),
  makes the account a customer, and can open an opportunity.
- **Pipeline:** your own stages (Settings → Pipeline) with probabilities; a board with per-currency totals; Lost needs
  a reason; Won makes the account a customer.
- **Customers and Customer 360:** people, deals, converted leads and one timeline that includes the deals' and leads'
  activity. **Dashboard:** open leads, 90-day conversion rate, pipeline by stage, won and lost this month.
- **Search:** the box at the top of every page (press `/`) finds people, organizations, leads, deals and products you
  may see.

## Inventory (Phase 6)
Switch the module on under **Settings → Modules**; every Inventory permission switches off with it (ADR-0011).
- **Warehouses:** Settings → Warehouses. Every workspace starts with MAIN and keeps at least one active warehouse; a
  warehouse that holds stock or has open orders can't be archived.
- **Stock:** on hand, reserved, available and on order per product and warehouse. Counts set on hand to what you
  found; transfers move available stock. Every change is one row in an append-only ledger, shown on the product page.
- **Purchase orders:** draft → place → receive, in as many parts as deliveries arrive. Placing makes the party a
  supplier.
- **Sales orders:** confirming reserves every line or lists what is short; fulfilling ships it; cancelling releases
  it. Confirming makes the party a customer.
- **Reorder:** per product and warehouse, a minimum and maximum with a preferred supplier. Suggestions explain
  themselves ("12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days …") and become draft
  purchase orders a person reviews and places.

## HelpDesk (Phase 7)
Switch the module on under **Settings → Modules**. Every HelpDesk permission switches off with it (ADR-0011).

- **Tickets** (`T-00001`, per workspace) belong to a requester from the directory. They can name a catalog product and
  any related record you may read, such as a sales order, a deal or a lead. A category routes a new ticket to its
  default assignee, and assigning someone else emails them.
- **SLA** targets per priority live under **Settings → HelpDesk** (defaults: urgent 1 h / 4 h, high 4 h / 1 d, normal
  8 h / 2 d, low 1 d / 5 d). The first public reply is the first response. Time *waiting on the customer* doesn't
  count towards resolution, and reopening restarts the resolution clock (ADR-0012).
- **Conversation**: a reply to the customer is emailed to the requester's address. An internal note stays inside the
  team. Logging a customer message moves a waiting ticket back to open. Messages can't be edited or deleted.
- **Context** next to each ticket: the requester, their previous tickets, possible duplicates and suggested
  knowledge-base articles, found with PostgreSQL full-text search. An article can be inserted into a reply.
- **Knowledge base**: draft → published (suggested on tickets) → archived.
- **Dashboard**: open tickets by status and priority, unassigned, breached and at-risk tickets, and for the last 30
  days the first-response and resolution times, the SLA met rates and the reopen rate.
- Party, Customer 360 and product pages show their tickets. Tickets have tasks, files and activity like any record.

## HRMS (Phase 8)
Switch the module on under **Settings → Modules**; every HRMS permission switches off with it (ADR-0011, ADR-0013).

- **Employees** are directory persons with an employment record: number, department, job role, manager, type, start
  and end dates, and optionally the team member account they sign in with. Hiring can create the person in the
  directory; the directory's employee role follows the record and can't be edited by hand while HRMS manages it.
  Ending employment cancels leave and training after the last day; it can be undone.
- **Job roles** list the skills they need (levels 1–5). Every employee page shows their **skill gaps** and recommends
  courses that teach enough; completing a course raises the skill.
- **Onboarding** templates (a "Standard onboarding" is ready) turn into tasks for HR, the manager and the new hire.
  For new hires to tick off their own items, their role needs task read (`collaboration.task.read`) as well as
  `hr.self.service`; otherwise their manager or HR ticks them off.
- **Leave** is counted in working days (weekends and the workspace's public holidays don't count) against yearly
  balances; HR can adjust a balance. Employees ask for leave under **My HR**; their manager is emailed, approvers decide
  under **HRMS → Leave**, and the employee is emailed. Nobody decides their own request.
- **Training** assigns courses with optional due dates; overdue ones are flagged.
- **Policies** are a searchable library; published ones are readable by everyone with self-service.
- **Overview**: headcount by department, joiners and leavers, who's away, pending approvals, overdue training,
  onboarding in progress and the most common skill gaps.
- Settings → HRMS holds departments, job roles, skills, leave types, public holidays and onboarding templates.

## AI assistance (Phase 12, part 1)
Answers and suggestions drawn only from your workspace's own documents (ADR-0014; spec
`docs/superpowers/specs/2026-11-20-ai-platform-core-design.md`).

- **What it does.** On a ticket, **AI assist** has **Suggest triage** (a category from the workspace's categories, a
  priority and a one-line reason; **Apply** is an ordinary ticket update) and **Draft reply** (a reply built from the
  knowledge base, placed in the reply box unsent; when no article answers the ticket the card says so and nothing is
  added to the reply). Under **HRMS → My HR**, **Ask about HR policies** answers a question from the published policies.
  Every output is labelled "AI-generated — check before use", and answers say "Answered from your workspace's documents"
  with a numbered list of the articles or policies they came from (an answer's `[1]` is the first source). The AI never
  changes data on its own.
- **Everything runs locally.** No API key, no hosted model, no paid service and no network at runtime. The `ai-service`
  container holds an open embedding model (`BAAI/bge-small-en-v1.5`, baked into the image at build time, so the first
  `docker compose ... --build` takes a few minutes). Generation is *extractive*: the answer is made of sentences quoted
  from the retrieved passages. It is less fluent than a language model and it can't invent anything, which is the point
  of the first slice. A hosted or local LLM can be plugged in later behind the same provider interface without
  changing the gateway or the UI.
- **How an answer is assembled and cited.** The backend (the *gateway*) is the only thing that touches the database.
  Published articles and policies are split into passages (about 800 characters) and embedded into a pgvector index.
  For a question the gateway embeds it, takes the five nearest passages **the caller is allowed to read**, and sends
  only that text to `ai-service`. `ai-service` embeds each sentence, keeps those closest to the question (at most four,
  at most two per passage) and returns them with passage markers. The gateway maps the markers back to sources inside
  the retrieved set, so a citation can only name a document that was actually used. If nothing is close enough it says
  so instead of guessing.
- **Permissions and opt-out.** Using AI needs `ai.assistant.use`; turning it off or on, setting the limit and
  rebuilding the index need `ai.settings.manage` (Settings → **AI**). Retrieval is filtered by tenant (row-level
  security) and by permission: knowledge-base passages need `helpdesk.article.read`, policy passages need
  `hr.self.service` (or `hr.policy.manage`). Unpublishing, archiving or editing a source removes its passages in the
  same transaction. A workspace can switch AI off (requests then get a clear refusal) and caps its requests per
  calendar month (UTC, default 500); there is also a per-user rate limit. Usage, latency and cited sources are
  recorded and audited for every request; the question and answer text are never stored.
- **Existing database volumes.** The Postgres image (`pgvector/pgvector:pg17`) already ships pgvector; what is new is
  the `vector` extension in the database. A fresh volume gets it from the new init script
  (`infra/docker/postgres/init/02-extensions.sh`); an existing one needs it once, before starting the new backend:
  `docker compose -f infra/docker/docker-compose.yml exec postgres psql -U postgres -d nexusops -c 'CREATE EXTENSION IF NOT EXISTS vector'`.
- **After upgrading, rebuild the index once.** Articles and policies are indexed when they are published, so documents
  published before this upgrade are not in the index (AI then answers that it couldn't find anything) until an admin
  opens Settings → **AI** and clicks **Rebuild index**. Do it once per existing workspace; new workspaces need nothing.
  **Rebuild index** is also the fix if answers ever seem to miss a document.

## Try the API (local)
```bash
curl -s -X POST localhost:8081/api/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"workspaceName":"Acme","slug":"acme","firstName":"Ada","lastName":"Owner","email":"ada@acme.test","password":"correct horse battery staple"}'
# open Mailpit (http://localhost:8025), click the verification link's token into:
curl -s -X POST localhost:8081/api/v1/auth/verify-email -H 'Content-Type: application/json' -d '{"token":"<token>"}'
curl -s -c /tmp/nx.cookies -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"workspace":"acme","email":"ada@acme.test","password":"correct horse battery staple"}'
curl -s localhost:8081/api/v1/me -H "Authorization: Bearer <accessToken>"
```
### Invite a teammate
```bash
TOKEN=<accessToken from the owner login above>
# 1. the owner creates a role (permission codes: GET /api/v1/permissions)
curl -s -X POST localhost:8081/api/v1/roles -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Support","description":"Read-only support","permissions":["identity.user.read","tenant.settings.read"]}'
# 2. the owner invites someone with that role (roleId from the response above)
curl -s -X POST localhost:8081/api/v1/invitations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"sam@acme.test","roleId":"<roleId>"}'
# 3. read the invitation token from the email in Mailpit (http://localhost:8025)
# 4. the invitee previews the invitation, then accepts it
curl -s "localhost:8081/api/v1/invitations/preview?token=<token>"
curl -s -X POST localhost:8081/api/v1/invitations/accept -H 'Content-Type: application/json' \
  -d '{"token":"<token>","firstName":"Sam","lastName":"Support","password":"another long passphrase"}'
# 5. the invitee logs in
curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"workspace":"acme","email":"sam@acme.test","password":"another long passphrase"}'
```
The API is rate-limited (defaults from spec §9, e.g. 10 logins per minute per IP and per account, 300 API calls per
minute per user); exceeding a limit returns `429` with a `Retry-After` header. Change the local defaults through
`nexusops.rate-limits.rules.*`.

The API contract is in `docs/api/openapi.json`; Swagger UI at http://localhost:8081/swagger-ui.html.

### Platform administration (NexusOps staff)
Platform users are created from the CLI only. It needs an interactive terminal and the database running (`make up`):

```bash
make platform-admin EMAIL=you@example.com                    # PLATFORM_ADMIN; add ROLE=PLATFORM_SUPPORT for read-only
```

Type a password (12+ characters) twice, scan the QR code with an authenticator app, and enter the code it shows.
Then sign in:

```bash
curl -i -X POST localhost:8081/api/v1/platform/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"…","code":"123456"}'
curl localhost:8081/api/v1/platform/tenants -H "Authorization: Bearer $PLATFORM_TOKEN"
curl -X POST localhost:8081/api/v1/platform/tenants/$TENANT_ID/suspend -H "Authorization: Bearer $PLATFORM_TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"Abuse report 42"}'
```

To recover, run one of these:
- `make platform-reset-totp EMAIL=…` for a lost phone;
- `make platform-reset-password EMAIL=…` for a lost password;
- `make platform-disable EMAIL=…` / `make platform-enable EMAIL=…` to disable or re-enable an account.

Ten consecutive wrong codes after a correct password disable the account (audited as `PlatformUserLockedOut`). To
recover, run `make platform-enable EMAIL=…` and also `make platform-reset-password EMAIL=…`, because whoever was
guessing codes knows the password.

In Docker, run `docker compose -f infra/docker/docker-compose.yml --profile app run --rm -it backend --nexusops.cli.command=create-platform-admin --nexusops.cli.email=you@example.com`.

Production must set `PLATFORM_TOTP_KEY`: 32 random bytes, base64, e.g. `openssl rand -base64 32`. Keep it out of the
database backups. Losing it means every operator must re-enrol.

## Tests
- `make test` runs the backend (including Testcontainers integration tests, so Docker must be running), the frontend and the ai-service.
- `make e2e` starts the full Docker stack (UI http://localhost:3000, Mailpit http://localhost:8025) and runs the
  Playwright journeys: sign up → verify → sign in → custom role → invite → accept → assign → audit trail, a
  platform admin suspending and reactivating a workspace, a lead becoming a customer with a won deal, and stock
  counted, sold, explained by a reorder suggestion and received in two parts, and an AI journey (`ai.spec.ts`: triage
  applied, a reply drafted from the knowledge base and a policy question answered with its source, all by the real
  `ai-service`). The stack stays up afterwards;
  `make down` stops it.
- `make e2e` seeds a local-only platform operator (`e2e-ops@nexusops.test`); `make reset-db` removes it, and the next
  `make e2e` seeds it again.

## Database roles
- The app connects as `nexusops_app` (not a superuser, no BYPASSRLS) and refuses to start as anything more privileged.
- Flyway migrates as `nexusops_owner`.
- See ADR-0002.
- `make reset-db` wipes the local database.

## Branching
Trunk-based: `main` is always releasable. Work happens on short-lived `feat/*` / `fix/*` branches that are merged via PR once CI passes.
