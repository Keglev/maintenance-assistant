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
| [ADR-005](ADR-005-spa-token-handling.md) | Keep the public-client SPA, harden it, and document the BFF | Accepted |
| [ADR-006](ADR-006-insider-threat-and-protocol-moderation.md) | Answer the insider threat with traceability and moderation | Accepted |
| [ADR-007](ADR-007-end-to-end-testing-strategy.md) | Test the rendered application in a browser | Accepted |
| [ADR-008](ADR-008-retrieval-measurement.md) | Measure retrieval against a golden question set before changing it | Proposed |
| [ADR-009](ADR-009-hybrid-retrieval.md) | Ground an answer on an exact term, without re-calibrating the threshold | Accepted |

New records start from [ADR-TEMPLATE.md](ADR-TEMPLATE.md). Numbering is sequential and never
reused. Planned: the Phase 5 extraction of the ingestion service and Kafka takes the next free
number — it was pencilled in as ADR-005, then as ADR-006, and has been overtaken twice by work that
shipped first.
