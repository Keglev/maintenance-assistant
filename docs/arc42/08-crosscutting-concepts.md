# 8. Cross-cutting Concepts

> **Stub — to be filled in Phases 2–3**, as each concept is implemented.

Concepts that will be documented here, with the decisions that already constrain them:

- **Domain model** — `MACHINE` → `PROTOCOL` → `CHUNK`; chunk = search unit, protocol = citation
  unit, machine = filter unit. Already specified in [DOMAIN-MODEL.md](../DOMAIN-MODEL.md).
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
