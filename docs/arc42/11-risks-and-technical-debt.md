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
| R-8 | **Free-text `error_code`** (no lookup table), and `downtime_minutes` captured but unused in Phase 1. | Minor inconsistency in the data model. | Accepted knowingly; both are open review points in [domain-model.md](../domain-model.md) §4. |

## 11.1 Upload abuse, quantified

R-4 above is stated in adjectives ("a few euros, not hundreds"). Since the write path became a
form anyone with a demo password can reach, it is worth stating in numbers. Every figure below is
measured, not estimated — the sources are the Phase 2 ingestion run and ADR-002.

**The write path is not open.** Uploading requires the `schichtleiter` realm role, checked
server-side (NFR-3); the demo simply publishes an account that has it. So the question is not
"what can a stranger do" but "what can one authenticated demo user do", which is a much smaller one.

**What a saturating attacker actually costs, per day:**

| Bound | Value | Source |
|---|---|---|
| Embedding calls per day, all users | 500 | `LLM_EMBEDDING_DAILY_CALL_BUDGET`, counted in Postgres |
| Protocols that buys, at ~1.1 chunks each | **~200/day** | 150 protocols → 166 chunks, measured |
| Cost of embedding the entire 150-protocol corpus | **EUR 0.00055** (27,713 tokens) | Phase 2 production run |
| Therefore, a full day of saturation | **well under one cent** | arithmetic on the two rows above |
| Disk for 200 protocols/day at a few KB each | **~1 MB/day** | corpus file sizes |
| Time to fill the 74 GB disk at that rate | **decades** | arithmetic |
| Nightly backup growth | negligible against a 752 KB dump | measured 2026-08-07 |

The daily embedding budget is the ceiling that matters, and it is deliberately the one guard that
lives in Postgres: it survives a restart and it is shared, so it holds regardless of how many
application instances run or how often they are redeployed. Everything else bounds the shape of the
spend rather than its total.

**What the other guards bound.** The size cap (256 KB per file, 512 KB per request) bounds the
single-request case: without it one request could hand the embedder a book, and the cost of a
protocol is proportional to its length rather than to the fact of it. The per-user rate limit
(10/minute) bounds the burst case, so a script cannot spend the day's budget in the first minute and
leave the demo answering "come back tomorrow" to everyone else — which is the real damage, and it is
availability damage rather than a bill. Content validation bounds the *garbage* case: an empty file
or a renamed PDF is refused before a row exists.

**The gap this left, and how it is closed.** Nothing above deletes a protocol that was accepted and
is simply bad — a plausible-looking protocol full of nonsense passes every guard here, because every
guard here is about size, shape and rate rather than about meaning. It then sits in the corpus and
can be retrieved and cited by a later answer. That was never an oversight to be closed with another
limit, and it is now closed by **moderation** ([ADR-006](../adr/ADR-006-insider-threat-and-protocol-moderation.md)):
an administrator — the role that cannot write, deliberately — reviews the whole corpus and remediates
it in one of two ways.

- **Correct it.** An in-place edit rewrites the document and **forces a re-index**, so the vectors
  retrieval searches are always the vectors of the text on screen. A wrong Massnahme is fixed by the
  person who spots it, rather than requiring the author to delete and retype the protocol. Machine
  and protocol type are not editable: they are the protocol's provenance rather than its content.
- **Archive it.** A deletion removes the **chunks** — which is what takes the protocol out of every
  answer, instantly and for every role — and keeps the row and the file. Removing garbage must not
  also destroy the evidence of who produced it, so the protocol stays readable to an administrator
  and to nobody else. There is **no restore**, by design. The archive is capped at 50 deletions per
  machine; beyond that the oldest are purged completely, which is what keeps this bounded like
  everything else here.

Both acts require a comment and are recorded in `moderation_event` with the actor and the time, so
the audit function has its own audit trail — and that ledger survives the purge that removes its
subject. Residual, and stated rather than closed: detection is still manual, one administrator is
still enough, and an answer already open on a screen can still show text that has since been
corrected.
