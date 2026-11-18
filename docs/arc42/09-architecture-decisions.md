# 9. Architecture Decisions

Decisions live as individual records in [`docs/adr/`](../adr/), which also holds their index.
Every architecture change gets an ADR; records are immutable once accepted, and a changed decision
becomes a new record or a dated revision inside the record.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](../adr/ADR-001-modular-monolith-first.md) | Modular monolith first (`ingestion` / `query` / `web`, Spring events); the ingestion service is extracted later | Accepted |
| [ADR-002](../adr/ADR-002-eu-hosted-llm-provider.md) | EU-hosted LLM and embedding provider: IONOS AI Model Hub, with a second IONOS model as the documented fallback; `bge-m3` embeddings at 1024 dimensions; provider calls through a plain `RestClient`, not Spring AI | Accepted (revised 2026-08-27) |
| [ADR-003](../adr/ADR-003-keycloak-for-iam.md) | Keycloak as identity provider; Authorization Code Flow + PKCE in the browser, OAuth2 Resource Server in the backend; realm exported and versioned | Accepted |
| [ADR-004](../adr/ADR-004-pgvector-for-vector-search.md) | PostgreSQL + pgvector rather than a dedicated vector database; relational filter and vector ranking in one query | Accepted |
| [ADR-005](../adr/ADR-005-spa-token-handling.md) | Keep the public-client SPA, harden it with security headers, and document the backend-for-frontend alternative | Accepted |
| [ADR-006](../adr/ADR-006-insider-threat-and-protocol-moderation.md) | Answer the insider threat with traceability and moderation, not with approval before indexing; duplicate detection warns and never blocks | Accepted |
| [ADR-007](../adr/ADR-007-end-to-end-testing-strategy.md) | Test the rendered application in a browser with Playwright against a real stack; the e2e and visual suites gate merges and deploys | Accepted (revised 2026-11-13) |
| [ADR-008](../adr/ADR-008-retrieval-measurement.md) | Measure retrieval against a golden question set before changing it | Accepted |
| [ADR-009](../adr/ADR-009-hybrid-retrieval.md) | Ground an answer on an exact term without re-calibrating the similarity threshold | Accepted |
| [ADR-010](../adr/ADR-010-docs-tree-conventions.md) | Name docs pages by what they are, and freeze citations that a checksum guards | Accepted (amended by ADR-013) |
| [ADR-011](../adr/ADR-011-guided-questions.md) | Show the user a question that works | Accepted |
| [ADR-012](../adr/ADR-012-shared-host-two-projects.md) | Run two portfolio projects on one host, and state what that costs | Accepted |
| [ADR-013](../adr/ADR-013-markdown-only-docs.md) | Write every document as linked markdown pages, never as plain text | Accepted |

**Planned:** the extraction of the ingestion service and the introduction of Kafka take the next
free ADR number once that change is actually made.

ADR-002 was decided on a measured two-round provider spike
([`docs/evidence/adr-002/RESULTS.md`](https://github.com/Keglev/maintenance-assistant/blob/main/docs/evidence/adr-002/RESULTS.md),
[PR #14](https://github.com/Keglev/maintenance-assistant/pull/14)) which reversed its original
proposal. One of its questions stays open by design: no provider-side hard spending cap exists,
which leaves NFR-7 resting on the application layer.

[Back to the arc42 overview](./)
