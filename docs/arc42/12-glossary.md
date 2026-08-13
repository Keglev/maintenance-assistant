# 12. Glossary

German shop-floor terms are kept in German where that is the real vocabulary of the domain; the
documentation is otherwise English.

| Term | Meaning |
|---|---|
| **Operator** | Machine operator. Receives operator-safe instructions only — never electrical or mechanical repair steps. |
| **Techniker** | Shop-floor technician (*Fachkraft*). Receives full technical answers including repair steps, and since v1.2 FILES protocols — but never corrects one, not even their own. |
| **Schichtleiter** | Shift supervisor. Writes protocols and, since v1.2, CORRECTS them with a mandatory reason, on their own view of the protocol list. May not approve, delete or read the archive — the corrector is never the approver, and correcting is not moderating. |
| **Admin** | System administrator. Manages users and roles in Keycloak; since v1.1 reviews and archives protocols, and since v1.2 APPROVES them. Does not correct one (2026-08-13) — the approver is never the corrector. |
| **Protocol** (*Protokoll*) | A maintenance record for one incident or service: symptom, cause, action, parts used. The **citation unit** of an answer. |
| **Störung / Wartung** | The two protocol types: fault (unplanned) and maintenance (planned). *Inspektion* was dropped — it lives in manufacturer manuals. |
| **Chunk** | A slice of a protocol's text with its embedding. The **search unit**. |
| **Machine** | The equipment a protocol belongs to. The **filter unit** of a search. |
| **Mode A — "Belegte Antwort"** | Grounded answer: every claim cited from an indexed protocol. The default. |
| **Mode B — "Allgemeiner Vorschlag — keine Quelle im Bestand"** | Explicitly labelled ungrounded general troubleshooting, used only when no relevant protocol exists. Never presented as fact. |
| **Operator-safe** | Actions an Operator may perform without a Fachkraft: cleaning a sensor, removing jammed material, loading a program. |
| **Approval** | An administrator vouching for a protocol, with their name and the time. NOT a gate: an unapproved protocol stays searchable and citable, because the admin may not review at a weekend and the factory does not stop. An edit resets it. |
| **Four eyes** | Author, corrector and approver are three different people. Enforced by the ROLE SPLIT — nobody holds two of the three jobs (2026-08-13) — with a ledger check as the belt: an approval is refused for a protocol the same person filed or last corrected. |
| **Escalation chain** | The organisational path Operator → Techniker → Schichtleiter → management. Context for the problem, **not** a system feature. |
| **RAG** | Retrieval-Augmented Generation: retrieve relevant chunks first, then let the model answer only from them. |
| **Embedding** | A vector representation of text. Multilingual here, so a German query can retrieve an English protocol without translation. |
| **pgvector** | PostgreSQL extension providing the `vector` column type and distance operators (`<=>` for cosine distance). |
| **Keycloak** | The self-hosted identity provider. Realm: `maintenance`. |
| **OIDC / OAuth2** | The authentication and authorization *protocols*. Keycloak is one *implementation* of them — see [ADR-003](../adr/ADR-003-keycloak-for-iam.md). |
| **PKCE** | Proof Key for Code Exchange: the extension that makes the Authorization Code Flow safe for a browser client that cannot hold a secret. |
| **Realm role** | A Keycloak role scoped to the realm, delivered in the JWT under `realm_access.roles` and mapped to a Spring Security authority. |
| **Resource Server** | The backend's OAuth2 role: it validates incoming JWTs but never issues them. |
| **CMMS** | Computerised Maintenance Management System — ticketing and work-order software. Explicitly **not** what this project is. |
| **PLC / SCADA** | Programmable Logic Controller / supervisory control system — the minimal software on the legacy machines in scope. |
| **DSGVO** | *Datenschutz-Grundverordnung*, the German name for the GDPR. |
| **arc42** | The template this architecture documentation follows. |
| **ADR** | Architecture Decision Record. |
