# 9. Architecture Decisions

Decisions live as individual records in [`docs/adr/`](../adr/). Every architecture change gets an
ADR; records are immutable once accepted, and a changed decision becomes a new record that
supersedes the old one.

| ADR | Decision | Status | Drives |
|---|---|---|---|
| [ADR-001](../adr/ADR-001-modular-monolith-first.md) | Modular monolith first (`ingestion` / `query` / `web`, Spring events); extract the ingestion service with Kafka in Phase 2 | Accepted | §4.1, §5, §7 |
| [ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md) | EU-hosted LLM and embedding provider: Nebius Token Factory primary, IONOS AI Model Hub fallback; multilingual embedding model as a hard requirement | **Proposed** — 1-day spike pending | §3.2, §4, NFR-1, NFR-7 |
| [ADR-003](../adr/ADR-003-keycloak-for-iam.md) | Keycloak as identity provider; Authorization Code Flow + PKCE in the browser, OAuth2 Resource Server in the backend; realm exported and versioned | Accepted | §3.2, §8, NFR-3 |
| [ADR-004](../adr/ADR-004-pgvector-for-vector-search.md) | PostgreSQL + pgvector rather than a dedicated vector database; relational filter and vector ranking in one query | Accepted | §4.3, §7, US-3 |

**Planned:** ADR-005 will record the Phase 5 extraction of the ingestion service and the
introduction of Kafka, once that change is actually made.

**Open decision:** ADR-002 is the only record not yet accepted. Its five open questions and its exit
criterion are listed in the record itself; Phase 1 work on the query module waits on it.
