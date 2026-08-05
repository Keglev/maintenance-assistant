# Architecture Documentation (arc42)

Architecture of **maintenance-assistant**, structured along the twelve
[arc42](https://arc42.org) sections.

Sections 1–4 and 9 are written; they are derived from the design documents in
[`docs/`](../) and must not contradict them. The remaining sections are stubs that fill up as the
implementation phases land — an empty section here is an honest "not built yet", not an oversight.

| # | Section | State |
|---|---|---|
| 01 | [Introduction and Goals](01-introduction-and-goals.md) | written |
| 02 | [Architecture Constraints](02-architecture-constraints.md) | written |
| 03 | [Context and Scope](03-context-and-scope.md) | written |
| 04 | [Solution Strategy](04-solution-strategy.md) | written |
| 05 | [Building Block View](05-building-block-view.md) | stub — Phase 1 |
| 06 | [Runtime View](06-runtime-view.md) | stub — Phase 3 |
| 07 | [Deployment View](07-deployment-view.md) | stub — Phase 1 |
| 08 | [Cross-cutting Concepts](08-crosscutting-concepts.md) | stub — Phase 2/3 |
| 09 | [Architecture Decisions](09-architecture-decisions.md) | written (links to ADRs) |
| 10 | [Quality Requirements](10-quality-requirements.md) | stub — Phase 4 |
| 11 | [Risks and Technical Debt](11-risks-and-technical-debt.md) | stub |
| 12 | [Glossary](12-glossary.md) | written |

**Sources of truth:** [REQUIREMENTS.md](../REQUIREMENTS.md), [DOMAIN-MODEL.md](../DOMAIN-MODEL.md),
[DECISIONS.txt](../DECISIONS.txt), [PROJECT-PHASES.txt](../PROJECT-PHASES.txt). Where this
documentation and those files disagree, those files win.

Diagrams are Mermaid sources (`.mmd`) under [`diagrams/`](diagrams/), rendered to SVG by
[`docs/scripts/generate-diagrams.mjs`](https://github.com/Keglev/maintenance-assistant/blob/main/docs/scripts/generate-diagrams.mjs).
