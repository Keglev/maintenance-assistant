# 11. Risks and Technical Debt

> **Stub — grows with the implementation.** The entries below are the risks already known and
> accepted at the end of the design phase.

| # | Risk / debt | Impact | Mitigation |
|---|---|---|---|
| R-1 | **ADR-002 is unresolved.** Provider, multilingual retrieval quality, real cost per query and the availability of hard billing limits are all unverified. | Blocks the query module; a failed billing-limit check would make the public demo unshippable as designed. | 1-day spike in Phase 0 with five explicit exit questions. |
| R-2 | **The fixer is not the documenter.** Write access is restricted to the Schichtleiter, so knowledge capture depends on the shift handover. | Protocols may be thin or lag behind the actual repair. | Accepted deliberately, in exchange for knowledge-base quality; documented as a known limitation. |
| R-3 | **In-process events are lost on crash** (no persistence). | An upload interrupted mid-indexing can be left in `RECEIVED`. | Accepted for Phase 1; it is precisely the argument that later justifies Kafka ([ADR-001](../adr/ADR-001-modular-monolith-first.md)). |
| R-4 | **Public demo, metered LLM.** | Abuse or a runaway loop costs money. | Three-layer NFR-7 guard; worst case is a few euros, not hundreds — the risk is surprise, not ruin. |
| R-5 | **Mode B can still mislead**, even when labelled ungrounded. | An operator could act on a generic suggestion. | Visual distinction from Mode A; operator-safe steps and escalation advice only for Operators; never presented as fact. |
| R-6 | **Single VPS, no redundancy.** | The demo is down if the host is down. | Accepted for a portfolio deployment; recovery is a fresh `docker compose up` from a snapshot. |
| R-7 | **Keycloak realm drift.** A change made in the admin console but not re-exported disappears on the next fresh start. | Demo users or roles could vanish. | The realm export is versioned in the repository and imported at startup; re-export is part of any realm change. |
| R-8 | **Free-text `error_code`** (no lookup table), and `downtime_minutes` captured but unused in Phase 1. | Minor inconsistency in the data model. | Accepted knowingly; both are open review points in [DOMAIN-MODEL.md](../DOMAIN-MODEL.md) §4. |
