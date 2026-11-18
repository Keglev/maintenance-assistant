# Product decisions

The decisions that shape the product, in short form: what was decided and why, one point each.
The formal records with context, options and consequences are the
[architecture decision records](adr/index.html); where a point here summarises an ADR, the ADR is
the one that decides. [Requirements](requirements.md) and the [domain model](domain-model.md) hold
the details.

| Page | Covers |
|---|---|
| This page | Product scope, roles, answer modes, corpus, data model, costs |
| [Architecture and ingestion](decisions-architecture.md) | Stack, provider, identity, upload rules, ingestion pipeline |
| [Query path and UI](decisions-query-and-ui.md) | Retrieval and answers, retrieval quality, interface, browser security |

## Product

- **Domain:** industrial maintenance (Instandhaltung), not procurement.
- **Problem:** maintenance knowledge is trapped in PDFs, scans, paper and people's heads; the night
  shift runs without a technician; faults get re-diagnosed although they were solved last month.
- **Not a ticketing or CMMS system;** the escalation chain is context only.
- **Roles:** Operator (operator-safe answers only), Techniker (full answers, files protocols),
  Schichtleiter (full answers, files and corrects protocols), Admin (Keycloak IT role and the
  corpus moderator: reads and archives any protocol, [ADR-006](adr/ADR-006-insider-threat-and-protocol-moderation.md);
  no query access, it has no maintenance question to ask).
- **Techniker may write, never edit** (decision 3 of 2026-08-11). Write access is the Techniker's
  and the Schichtleiter's, so the person who fixed a fault files it. Correcting stays with the
  Schichtleiter, and nobody corrects their own protocol: that is where the quality control sits.
- **Two answer modes, visually distinct** (the anti-hallucination mechanism):
  Mode A "Belegte Antwort" is grounded, every claim cited; Mode B "Allgemeiner Vorschlag — keine
  Quelle im Bestand" is labelled ungrounded troubleshooting. Operators get operator-safe steps and
  escalation advice only.
- **Corpus:** synthetic protocols (generated with Claude, documented in [AI usage](ai-usage.md)),
  about 70% German and 30% English, with a quality mix that includes messy ones. Demo seeds: E-47
  on Presse 3 (three protocols), one Mode B gap, one German question that matches an English
  protocol.
- **No translation anywhere:** texts are stored as written; multilingual embeddings bridge German
  and English.
- **Search scope:** the exact machine only; "similar machines" is a candidate for later.
- **Protocol types:** STOERUNG and WARTUNG. INSPEKTION is not a type; inspections live in the
  manufacturers' manuals.
- **UI:** bilingual DE/EN.

## Data model

- Three entities: MACHINE -> PROTOCOL -> CHUNK. The chunk is the search unit, the protocol the
  citation unit, the machine the filter unit.
- Users are not in the database: Keycloak claims only; `uploaded_by` is the username string.
- Original files live on a Docker volume with their path in the database, no BLOBs.
- CHUNK carries a denormalised `machine_id` and `error_code`, so a filtered vector search is one
  query.
- `error_code` is free text: real plants mix catalogues, and a code sometimes points to the wrong
  problem, so semantic search on the symptom text is the actual retrieval path and the code only a
  hint.
- Status lifecycle: RECEIVED -> INDEXED / FAILED.

## Costs

- **Monthly:** the VPS is about 95% of the cost, the LLM API about 5%. Corpus size is not a cost:
  embedding the whole corpus costs under a cent once, and the cost per query does not depend on
  corpus size. So the corpus is kept large for a richer demo.
- **Server:** Hetzner CX33 (x86_64, 4 vCPU, 8 GB). ARM was chosen first and dropped: the price
  advantage disappeared in Hetzner's 2026-06 restructuring and CAX was out of stock. The deploy
  workflows build `linux/amd64` only: an arm64 image built under emulation crashed a deploy
  (`qemu: uncaught target signal 4` during `npm ci`) and had no consumer. Moving to ARM is a server
  swap plus one line per deploy workflow, better with a native arm64 runner.
- **Not shrinking to 4 GB:** about EUR 5 a month saved against an out-of-memory risk during
  indexing, which is a broken-demo risk.
- **Pausing** means snapshot, then delete the server (a stopped server still bills in full);
  pay during application phases, snapshot only in real pauses.
- **Token abuse protection** (NFR-7), three layers: the provider's cost alerts (there is no hard
  spending cap at IONOS), the application's limits (per-user rate limit, daily call budgets in
  Postgres, capped output tokens, an answer cache), and daily usage logging. Worst case, a thousand
  spam queries cost a few euros: the risk is surprise, not ruin.

[Back to the docs home](index.html)
