# Architecture Documentation (arc42)

Architecture of **maintenance-assistant**, structured along the twelve
[arc42](https://arc42.org) sections.

The sections are derived from the design documents in [`docs/`](../) and must not contradict
them. "Partial" marks a section that documents part of the system and says which part; "stub" marks
a section with headings and no content yet.

| # | Section | State |
|---|---|---|
| 01 | [Introduction and Goals](01-introduction-and-goals.md) | written |
| 02 | [Architecture Constraints](02-architecture-constraints.md) | written |
| 03 | [Context and Scope](03-context-and-scope.md) | written |
| 04 | [Solution Strategy](04-solution-strategy.md) | written |
| 05 | [Building Block View](05-building-block-view.md) | partial |
| 06 | [Runtime View](06-runtime-view.md) | partial |
| 07 | [Deployment View](07-deployment-view.md) | written |
| 07a | [Ops Rules](07a-ops-rules.md) | written |
| 08 | [Cross-cutting Concepts](08-crosscutting-concepts.md) | partial |
| 09 | [Architecture Decisions](09-architecture-decisions.md) | written (links to ADRs) |
| 10 | [Quality Requirements](10-quality-requirements.md) | stub |
| 11 | [Risks and Technical Debt](11-risks-and-technical-debt.md) | written |
| 12 | [Glossary](12-glossary.md) | written |

**Sources of truth:** [requirements.md](../requirements.md), [domain-model.md](../domain-model.md),
[product decisions](../decisions.md). Where this
documentation and those files disagree, those files win.

Diagrams are Mermaid sources (`.mmd`) under [`diagrams/`](diagrams/), rendered to SVG by
[`docs/scripts/generate-diagrams.mjs`](https://github.com/Keglev/maintenance-assistant/blob/main/docs/scripts/generate-diagrams.mjs).
