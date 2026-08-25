# 8. Cross-cutting Concepts

> **Partial — filled in as each concept is implemented.** §8.1 (persistence) is done;
> the rest is still a stub list.

## 8.1 Persistence and the database schema

`MACHINE` → `PROTOCOL` → `CHUNK`: machine is the filter unit, protocol the citation unit, chunk
the search unit. [domain-model.md](../domain-model.md) holds the conceptual model and the reasoning;
this section records the schema as it actually exists in the database.

The schema is owned by **Flyway**, not by JPA. `backend/src/main/resources/db/migration/` is the
only thing that creates or changes tables; there is no `ddl-auto` anywhere. Two consequences worth
stating: the database is reproducible from an empty volume by starting the application, and a
schema change is a reviewable file in a pull request rather than a side effect of an entity edit.

| File | Kind | Purpose |
|---|---|---|
| `V1__baseline_schema.sql` | versioned | The three tables, their constraints, and every index |
| `R__seed_machines.sql` | repeatable | The 10 demo machines — reference data, re-applied when edited |

### Tables

**`machine`** — equipment master data.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `machine_no` | `varchar(32)` | **unique**; the plant-facing identifier (`PR-03`) |
| `name`, `type` | `varchar(128)` | not null |
| `manufacturer` | `varchar(128)` | |
| `year_built` | `smallint` | check `1950..2100`; legacy equipment is the normal case |
| `control_type` | `varchar(16)` | check `PLC \| SCADA \| CNC \| TERMINAL` |
| `location` | `varchar(128)` | hall / line |
| `created_at` | `timestamptz` | default `now()` |

**`protocol`** — one maintenance or fault report; the unit an answer cites.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `machine_id` | `uuid` | FK → `machine`, `ON DELETE CASCADE` |
| `incident_date` | `date` | not null |
| `protocol_type` | `varchar(16)` | check `STOERUNG \| WARTUNG` |
| `error_code` | `varchar(64)` | nullable, **free text by decision** — a retrieval hint, not a key |
| `title` | `varchar(255)` | not null |
| `symptom`, `cause`, `action`, `parts_used` | `text` | nullable; extraction from an uploaded file fills `symptom` only |
| `downtime_minutes` | `integer` | nullable, check `>= 0` |
| `technician_initials` | `varchar(8)` | |
| `language` | `varchar(2)` | check `de \| en`; nothing is ever translated |
| `source_file` | `varchar(512)` | path on the Docker volume — **no BLOBs in Postgres** |
| `status` | `varchar(16)` | check `RECEIVED \| INDEXED \| FAILED`, default `RECEIVED` |
| `uploaded_by` | `varchar(128)` | Keycloak username string, **deliberately no FK** |
| `created_at`, `updated_at` | `timestamptz` | default `now()` |

**`chunk`** — the embedded search unit.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `protocol_id` | `uuid` | FK → `protocol`, `ON DELETE CASCADE` |
| `chunk_index` | `integer` | position within the protocol; unique with `protocol_id` |
| `content` | `text` | not null |
| `embedding` | `vector(1024)` | nullable until indexing completes; **1024 = BAAI/bge-m3 (ADR-002)** |
| `language` | `varchar(2)` | check `de \| en` |
| `machine_id` | `uuid` | FK → `machine`; **denormalized** from protocol |
| `error_code` | `varchar(64)` | **denormalized** from protocol, nullable |
| `created_at` | `timestamptz` | default `now()` |

`chunk_index` is the one column not in domain-model.md. It makes re-indexing a
delete-and-reinsert with a stable identity and gives citations a deterministic order.

### Three schema-level decisions

- **Users are not rows.** Identity, roles and credentials live in Keycloak (ADR-003). The schema's
  only trace of a person is `protocol.uploaded_by`, a username string with no foreign key — there
  is nothing for it to point at, by design.
- **`vector(1024)` is a hard coupling to the embedding model.** pgvector encodes dimensionality in
  the column type, so switching away from bge-m3 is a migration plus a full re-embedding of the
  corpus, not a configuration change. ADR-002 is what makes 1024 the right number.
- **Denormalizing `machine_id` and `error_code` onto `chunk`** is what lets the relational filter
  and the vector ranking share one query and one index scan (ADR-004). The price is that both
  columns must be rewritten if a protocol moves to another machine; that only happens during
  ingestion, where the chunks are rewritten anyway.

### Indexes

The Phase 3 retrieval path filters by machine (optionally narrowed by an error-code hint) and then
ranks by cosine distance. The indexes exist to serve exactly that shape.

| Index | On | Serves |
|---|---|---|
| `ix_chunk_machine_id` | `chunk (machine_id)` | the US-3 machine filter |
| `ix_chunk_machine_error` | `chunk (machine_id, upper(btrim(error_code)))`, partial | the error-code hint, case- and whitespace-insensitive |
| `ix_chunk_protocol_id` | `chunk (protocol_id, chunk_index)` | citation assembly, cascade deletes |
| `ix_chunk_embedding_hnsw` | `chunk` HNSW `vector_cosine_ops` | vector ranking — see ADR-004 |
| `ix_protocol_machine_date` | `protocol (machine_id, incident_date DESC)` | protocol list per machine, newest first |
| `ix_protocol_machine_error` | `protocol (machine_id, upper(btrim(error_code)))`, partial | the same hint from the protocol side |
| `ix_protocol_pending` | `protocol (status, created_at)` where `status <> 'INDEXED'` | the ingestion worklist and the "what failed?" view |

Both `error_code` indexes are on the **normalized** expression, because a free-text code typed by
humans arrives as `E-47`, `e-47` and `E-47 ` alike; a query that normalizes the same way keeps the
index. Both are **partial**, because most rows carry no code at all.

The vector index is **HNSW rather than IVFFlat**, and at this corpus size it is headroom rather
than a present-day speed-up — the reasoning and the caveats are in
[ADR-004](../adr/ADR-004-pgvector-for-vector-search.md), *Consequences*, note of 2026-08-07.

## 8.2 Browser-side security and token handling

The Angular client is a Keycloak **public client** (Authorization Code + PKCE), so the access token
lives in the browser — in `sessionStorage`, tab-scoped, for the 15 minutes the realm grants it. The
full reasoning, including why a backend-for-frontend was rejected for this project and named as the
enterprise-grade evolution, is [ADR-005](../adr/ADR-005-spa-token-handling.md). Two things belong
here because they cut across the whole deployment:

**The threat split.** Malware on the user's own machine is out of scope — it can read `HttpOnly`
cookies from the browser's store, log the password and read the screen, and no web-application
architecture defends against a compromised endpoint. Cross-site scripting reading the token out of
the SPA **is** in scope, and is what the controls below address.

**Where the controls live.** At the edge, in
[`docker/caddy/Caddyfile`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/caddy/Caddyfile),
not in the frontend image: one place states the policy for every response on the application
hostname, including the API and the OpenAPI UI, and the frontend image stays environment-agnostic.
`Content-Security-Policy` (`script-src 'self'`, `connect-src` limited to the origin and the Keycloak
host, `frame-ancestors 'none'`), `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`
and HSTS. Two consequences a reader should carry away:

- `style-src` keeps `'unsafe-inline'` because Angular injects component styles at runtime and the
  alternative is a per-request nonce this static deployment cannot produce. It is a decision, and
  ADR-005 argues it.
- The Caddyfile is **not** deployed by CI (§7.5). This hardening is inert until a person copies the
  file to the host and reloads Caddy.

Application-level authorization is unaffected by any of it: roles are enforced by the backend
resource server, and the UI hiding a button has never been the control (NFR-3).

## 8.3 Remaining concepts

> **Stub — to be filled in Phases 2–3**, as each concept is implemented.

- **Security** — OIDC login, JWT validation, realm roles as Spring Security authorities, and
  role-based **answer filtering** as a server-side concern, never a UI-only one.
- **Multilingualism** — nothing is translated; texts are stored as written and a multilingual
  embedding model bridges DE↔EN retrieval. The UI language (US-8) is independent of the corpus
  language.
- **Answer modes** — the Mode A / Mode B decision is a runtime similarity threshold, configurable,
  not a stored attribute.
- **Asynchronous processing** — Spring application events in Phase 1; the same seam becomes Kafka in
  Phase 2.
- **Cost and abuse control (NFR-7)** — per-user rate limiting, a global daily call budget, a capped
  answer length, a query cache, and daily token-usage logging.
- **Data protection** — synthetic corpus only; no query logging of personal data (if query logging
  is added, it stores role, timestamp and answer mode — never the username).
- **Error handling** — a failed indexing run ends in status `FAILED` and stays visible to the
  Schichtleiter; budget exhaustion and provider outages degrade to a message, never an error page.
- **Testing** — JUnit/Mockito on every push; the minimum set is mode routing, role filtering and the
  budget guard.
