# 9. Architecture Decisions

Decisions live as individual records in [`docs/adr/`](../adr/). Every architecture change gets an
ADR; records are immutable once accepted, and a changed decision becomes a new record that
supersedes the old one.

| ADR | Decision | Status | Drives |
|---|---|---|---|
| [ADR-001](../adr/ADR-001-modular-monolith-first.md) | Modular monolith first (`ingestion` / `query` / `web`, Spring events); extract the ingestion service with Kafka in Phase 2 | Accepted | §4.1, §5, §7 |
| [ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md) | EU-hosted LLM and embedding provider: IONOS AI Model Hub primary, Nebius Token Factory documented fallback; `bge-m3` embeddings at 1024 dimensions, similarity threshold as a configuration property | Accepted | §3.2, §4, NFR-1, NFR-2, NFR-7 |
| [ADR-003](../adr/ADR-003-keycloak-for-iam.md) | Keycloak as identity provider; Authorization Code Flow + PKCE in the browser, OAuth2 Resource Server in the backend; realm exported and versioned | Accepted | §3.2, §8, NFR-3 |
| [ADR-004](../adr/ADR-004-pgvector-for-vector-search.md) | PostgreSQL + pgvector rather than a dedicated vector database; relational filter and vector ranking in one query | Accepted | §4.3, §7, US-3 |

**Planned:** ADR-005 will record the Phase 5 extraction of the ingestion service and the
introduction of Kafka, once that change is actually made.

**All four records are accepted.** ADR-002 was decided on a measured two-round provider spike
([`spike/adr-002/RESULTS.md`](https://github.com/Keglev/maintenance-assistant/blob/main/spike/adr-002/RESULTS.md),
[PR #14](https://github.com/Keglev/maintenance-assistant/pull/14)) which reversed its original
proposal. Two of its questions stay open by design and are tracked in the record: Spring AI
compatibility with Spring Boot 4.1 (deferred to Phase 3 — the spike was Python, so it measured the
provider and not the Java integration), and the fact that no provider-side hard spending cap
exists, which leaves NFR-7 resting on the application layer.
