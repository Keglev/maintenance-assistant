# ADR-001: Modular Monolith First, Service Extraction in Phase 2

**Status:** Accepted · **Date:** 2026-08-05

## Context
The system needs asynchronous document processing (upload → extract → embed → index) and must be demoable on a single ~8 GB VPS. The developer is a single person with a 3–4 week budget. Regional job ads ask for microservices and Kafka experience, but a portfolio project must above all *work* when a recruiter clicks it (a broken demo was concrete prior feedback).

## Decision
Phase 1 is one Spring Boot application, internally modularized by package (`ingestion`, `query`, `web`), with asynchronous processing via Spring application events. 3 containers total (app, PostgreSQL+pgvector, Keycloak).

Phase 2 (only after Phase 1 is stable and deployed): extract the `ingestion` module into its own service and introduce Apache Kafka for the `document.received` → `document.indexed` event flow.

## Consequences
- (+) Fully demoable early; one deployment unit; debugging without distributed tracing.
- (+) The module boundary drawn in Phase 1 is the extraction seam for Phase 2 — the domain model does not change, only the event transport (in-process events → Kafka topics).
- (+) Git history documents architectural evolution driven by need, not fashion.
- (−) Phase 1 shows no running Kafka; mitigated by this ADR and the Phase 2 plan being public in the repo.
- (−) In-process events are lost on crash (no persistence); acceptable for Phase 1, and precisely the argument that later justifies Kafka.

## Alternatives rejected
- **Microservices from day 1:** highest interview-keyword density, but triples operational complexity (service discovery, distributed failures, per-service pipelines) for a solo developer, and risks shipping nothing demoable. "Monolith first" is established industry guidance (Fowler).
- **Skipping Kafka entirely:** Kafka appears in ~most regional Java ads; dropping it forfeits the main skill-gap goal. Deferred, not dropped.
