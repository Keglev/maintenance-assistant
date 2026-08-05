# Architecture Overview

The short way into the architecture. It condenses arc42 sections 1, 3 and 4; the full
documentation lives under [Architecture](architecture/index.html), and every decision behind it in
the [decision records](adr/index.html).

## The problem

Maintenance knowledge in manufacturing is trapped in scattered PDFs, scans and paper manuals — and
in the heads of experienced technicians. Many machines run 15–20 years on minimal software
(PLC/SCADA only), so the machine itself offers little diagnostic help beyond an error code. When a
machine stops, every escalation step costs downtime, and far too often the fault was already solved
last month but nobody can find the protocol. The pain peaks on night shifts with no technician on
site.

**maintenance-assistant** lets shop-floor staff search maintenance protocols in natural language
(German or English) and answers with citations to the source documents. It is explicitly **not** a
ticketing or CMMS system; the escalation chain is organisational context, not a system feature.

## Who uses it

| Role | What they get |
|---|---|
| **Operator** | Search; operator-safe instructions only — clean a sensor, remove material, change a program — never electrical or mechanical repair steps. Told to escalate immediately when a Fachkraft is required. |
| **Techniker** | Search; full technical answers including repair steps, with citations to verify against and a machine filter to keep results relevant. |
| **Schichtleiter** | All of the above, plus the sole write access: creates and uploads protocols and sees their processing status. |
| **Admin** | Manages users and role assignments in Keycloak, without code changes. |

## System context

Users reach an Angular client in the browser. Authentication is delegated to **Keycloak** (realm
`maintenance`) over OIDC; the backend never sees a password. The **Spring Boot** application
validates the resulting JWT as an OAuth2 Resource Server and applies role-based answer filtering
server-side. Protocols and their embeddings live in one **PostgreSQL** instance with **pgvector**,
and the only outbound call at query time goes to an **EU-hosted, OpenAI-compatible LLM endpoint**.
Original uploaded files sit on a Docker volume; the database stores only their path.

## Solution strategy

The system is a **modular monolith**: one Spring Boot application internally split into `ingestion`,
`query` and `web`, communicating through Spring application events. That boundary is the seam along
which `ingestion` becomes its own service with Kafka in Phase 2 — a planned evolution rather than an
afterthought.

Retrieval is one SQL statement that combines the relational machine filter with cosine ranking over
the chunk table, which is why the vectors live in PostgreSQL instead of a dedicated vector database.
A similarity threshold on the retrieved hits decides between the two answer modes at runtime; the
mode is behaviour, not stored state. See the [AI & LLM approach](llm-approach.html) for what that
means for the answers themselves.

Three containers run the whole stack — application, PostgreSQL, Keycloak — on a single 8 GB VPS via
one `docker compose up`.

## Where to go next

- [Architecture Decision Records](adr/index.html) — why each of those choices, and what was rejected
- [Architecture (arc42)](architecture/index.html) — the full documentation
- [Requirements](REQUIREMENTS.html) — user stories and the seven non-functional requirements
- [Domain model](DOMAIN-MODEL.html) — the three entities and the reasoning behind them
