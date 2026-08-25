# 1. Introduction and Goals

## 1.1 Requirements overview

Maintenance knowledge in manufacturing is trapped in scattered PDFs, scans and paper manuals — and
in the heads of experienced technicians. Many machines run 15–20 years on minimal software
(PLC/SCADA only), so the machine itself offers little diagnostic help beyond an error code. When a
machine stops, every escalation step costs downtime, and far too often the fault was already solved
last month but nobody can find the protocol. The pain peaks on night shifts with no technician on
site.

**maintenance-assistant** lets shop-floor staff search maintenance protocols in natural language
(German or English) and answers with citations to the source documents. All data processing happens
exclusively in the EU. It is explicitly **not** a ticketing or CMMS system; the escalation chain is
organisational context, not a system feature.

Full requirements: [requirements.md](../requirements.md). Phase 1 scope covers user stories US-1 to
US-8.

## 1.2 Quality goals

Ordered by architectural weight — these are the drivers that shaped the decisions in section 9.

| # | Quality goal | Motivation | Source |
|---|---|---|---|
| 1 | **Verifiability of answers** | Two visually distinct answer modes: Mode A "Belegte Antwort" (grounded, every claim cited) and Mode B "Allgemeiner Vorschlag — keine Quelle im Bestand" (clearly labelled as ungrounded). An unverifiable answer in maintenance is worse than none. | NFR-2 |
| 2 | **Data residency / DSGVO** | All document and query processing on EU infrastructure; LLM inference via an EU-hosted provider; no data used for model training. | NFR-1 |
| 3 | **Security & role-correct answers** | Keycloak/OIDC authentication; role-based access **and** role-based answer filtering enforced server-side — an Operator must never receive an electrical or mechanical repair step. | NFR-3 |
| 4 | **Operability** | The full stack runs on a single ~8 GB VPS via one `docker compose up`; CI on every push. | NFR-5 |
| 5 | **Cost control** | LLM spend bounded at three layers (provider billing limits, application rate limit + daily budget + caching, usage logging); budget exhaustion degrades gracefully with a user-facing message, never an error page. | NFR-7 |
| 6 | **Performance** | Target ≤ 10 s per answer, up to 30 s acceptable; upload confirmation immediate, indexing asynchronous. | NFR-4 |
| 7 | **AI transparency** | The repository documents which AI tools were used, what was generated versus self-written, and how it was verified. | NFR-6 |

## 1.3 Stakeholders

| Role | Expectation |
|---|---|
| **Operator** (machine operator, incl. legacy terminals with intranet access) | Search; receives operator-safe instructions only — clean a sensor, remove material, change a program — never electrical or mechanical repair steps. Told to escalate immediately when a Fachkraft is required. |
| **Techniker** (shop-floor technician) | Search; receives full technical answers including repair steps, with citations to verify against, and machine filtering to keep results relevant. **Files protocols** (v1.2) — they are the person at the machine when it is fixed — and **may never correct one, not even their own**: a correction is requested from the Schichtleiter. |
| **Schichtleiter** (shift supervisor) | All of the above, plus writing and **correcting** protocols (v1.2) with a mandatory reason and a forced re-index — since 2026-08-13 the *only* role that may correct, and since 2026-08-14 able to reach the screen that does it. May not approve: the corrector is never the approver, and they see no delete, no approval control and no archive. |
| **Admin** (IT role only, plus moderation since v1.1) | Manages users and roles in Keycloak; reviews the corpus, archives protocols, and since v1.2 **approves** them. **Does not correct** (2026-08-13): the approver is never the corrector, so nobody holds two jobs in the chain. Since 2026-08-15 the role split is the *whole* rule — the runtime check that compared usernames is gone, and `RoleMatrixIT` fails the build if any role ever gains both a corpus-write authority and the approve authority. |
| **Project owner / developer** | Solo developer, 3–4 week budget; the system is also a portfolio artefact and must demonstrably work on demand. |
| **Recruiter / reviewer** (demo audience) | A ≤ 90-second walkthrough that works on a fresh browser and a phone: login, two roles, one Mode-B case, one upload. |
