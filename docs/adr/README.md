# Architecture Decision Records

Every architecture change gets an ADR — a rule carried across all project phases. Records are
immutable once accepted: a changed decision becomes a new ADR that supersedes the old one, rather
than an edit to history.

| ADR | Title | Status |
|---|---|---|
| [ADR-001](ADR-001-modular-monolith-first.md) | Modular monolith first, service extraction in Phase 2 | Accepted |
| [ADR-002](ADR-002-eu-hosted-llm-provider.md) | EU-hosted LLM & embedding provider | **Proposed** (spike pending) |
| [ADR-003](ADR-003-keycloak-for-iam.md) | Keycloak as identity provider | Accepted |
| [ADR-004](ADR-004-pgvector-for-vector-search.md) | PostgreSQL + pgvector instead of a dedicated vector DB | Accepted |

New records start from [ADR-TEMPLATE.md](ADR-TEMPLATE.md). Numbering is sequential and never
reused. Planned: ADR-005 will document the Phase 5 extraction of the ingestion service and Kafka.
