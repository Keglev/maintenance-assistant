# maintenance-assistant

**A machine stops at 02:00. No technician on site.**

The operator reads an error code off a 2005 hydraulic press that offers no diagnostics beyond
that code. The fix is written down — in a PDF, a scan, or a paper folder in a cabinet two halls
away. Often it was solved last month. Nobody can find it. So the escalation chain runs its
course (operator → technician after 10 minutes → shift supervisor → management), and every step
costs downtime.

This project makes that knowledge findable. Shop-floor staff ask in plain German or English —
*"Presse 3 zeigt Fehler E-47, gab es das schon?"* — and get an answer **with citations to the
source protocols**, so it can be verified rather than trusted. Answers are filtered by role: an
operator sees only what an operator may safely do (clean a sensor, clear a jam, load a program)
plus a clear "escalate now" when a technician is required. When no protocol matches, the system
says so instead of inventing one. All data processing happens exclusively on EU infrastructure.

It is not a ticketing or CMMS system. The escalation chain is organizational context, not a
feature.

## Planned architecture

A modular monolith in Spring Boot 4.1 (Java 21) with internal modules for ingestion, query and
web, fronted by a thin Angular client — three containers in total, running on a single 8 GB VPS
via one `docker compose up`. Protocols are chunked, embedded through an EU-hosted LLM provider,
and stored in PostgreSQL with pgvector, so a single SQL query combines the machine filter with
vector ranking. Authentication and the four roles come from Keycloak via OIDC (Authorization
Code Flow + PKCE in the browser, OAuth2 Resource Server in the backend), with role-based answer
filtering enforced server-side.

**Status: in development, Phase 1 (walking skeleton).**

## Demo

_Placeholder — the hosted demo and its credentials land at the end of Phase 4._

## Documentation

_Placeholder — will link to the published documentation site (GitHub Pages, `gh-pages` branch)._

In the repository meanwhile:

- [docs/arc42/](docs/arc42/) — architecture documentation (arc42)
- [docs/adr/](docs/adr/) — architecture decision records
- [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) — requirements and non-functional requirements
- [docs/DOMAIN-MODEL.md](docs/DOMAIN-MODEL.md) — entities and data-model rationale
- [AI-USAGE.md](AI-USAGE.md) — how AI was used in this repository

## License

[MIT](LICENSE)
