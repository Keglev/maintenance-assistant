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
| **Techniker** | Search; full technical answers including repair steps, with citations to verify against and a machine filter to keep results relevant. Uploads protocols, but never edits one. |
| **Schichtleiter** | All of the above, plus correcting: the only role that may edit a protocol, and sees the processing status of uploads. |
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

## Try it in two minutes

Sign in at [maintenance.smartsupply.com.de](https://maintenance.smartsupply.com.de) as `techniker`; the
password is on the sign-in page.

1. **Pick Presse 3 (PR-03) and click an example question.** Four suggestions appear under the question
   box in the interface language. A click fills the box and does not submit: you still read the question
   and press Ask yourself, so what you learn is the shape of a question that works. The answer arrives
   with its sources as cards you can open. [ADR-011](adr/ADR-011-guided-questions.html) ·
   [the questions themselves](https://github.com/Keglev/maintenance-assistant/blob/main/backend/src/main/resources/examples/example-questions.json)
2. **Switch the interface to EN, pick Kompressor 1 (KP-01), and ask in German:** *"Kompressor schaltet
   nach einer halben Stunde wegen zu hoher Temperatur ab"*. The protocol behind that fault is written in
   English. There is no translation step anywhere: one embedding model holds both languages in one
   space. [The golden questions](https://github.com/Keglev/maintenance-assistant/blob/main/backend/src/test/resources/retrieval/golden-questions.json), case G10
3. **Stay on Kompressor 1 and ask "sensor fehler".** None of its 14 protocols is about a sensor. The
   answer comes back as a general suggestion, labelled ungrounded, with no source area at all, and it
   says how many protocols the machine does have. [The corpus](https://github.com/Keglev/maintenance-assistant/blob/main/backend/src/main/resources/corpus/protocols.ndjson) ·
   [the two answer modes](llm-approach.html)

## Where to go next

- [Architecture Decision Records](adr/index.html) — why each of those choices, and what was rejected
- [Architecture (arc42)](architecture/index.html) — the full documentation
- [Requirements](requirements.html) — user stories and the seven non-functional requirements
- [Domain model](domain-model.html) — the three entities and the reasoning behind them
- [Product decisions](decisions.html) — the decisions that shape the product, in short form
- [Live provider tests](live-provider-tests.html) — the tests that call the paid provider, and why CI leaves them out
- [Run it locally](run-locally.html) — the whole application on a workstation, with a free provider stub
