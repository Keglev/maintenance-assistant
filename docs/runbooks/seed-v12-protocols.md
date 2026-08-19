# Runbook — bring the 15 v1.2 protocols into production

**Status:** proposed, never executed. Written 2026-08-19 (PR #62). No step below has been run
against production by its author; every command was exercised against the local development stack.

**Who runs this:** Carlos, on the server, at `/opt/maintenance-assistant`.

**Time:** about 10 minutes, most of it waiting for two container recreations.
**Cost:** about **EUR 0.00005** (see [step 6](#6-cost-and-what-it-should-look-like)).

> This document is deliberately not published to the documentation site. It is for the person
> holding SSH, the same way the Keycloak theme runbook lives beside the theme.

## Why

v1.2 added 15 easier-fault protocols so that the approval queue has something real in it. **They
shipped as code and never as data.** Production holds the 150 originals, every one of them
`APPROVED` by `system:corpus-seed`, so:

- **the approve queue is empty** and the v1.2 trust chain — the thing v1.2 exists to demonstrate —
  cannot be shown by clicking;
- questions like *"Presse reagiert beim Einschalten überhaupt nicht"* have no protocol to find.

Getting these 15 in is what makes v1.2 visible. It is not a data top-up.

## What this must not do

**The 150 original protocols must not be re-embedded, re-written or touched.** They are correct,
they cost money to produce, and re-embedding them would spend that money again for no change.

**This is safe by construction, not by care:** the seed loader inserts with the protocol's own fixed
UUID and `ON CONFLICT (id) DO NOTHING`, so a row that already exists is skipped by the database
rather than by a decision in the loader. Indexing then only ever selects protocols in status
`RECEIVED`; the 150 are `INDEXED` and are not selectable. Both guarantees are checked in
[step 3](#3-verify-the-seed-added-only-15-rows) before any embedding is paid for.

## The race this is ordered around

`CorpusSeedRunner` and `IngestionBacklogRunner` are both `ApplicationRunner` beans and **neither
declares an order**, so with both flags set in one startup the backlog can run *before* the seed has
inserted anything. It then finds nothing to do, and the 15 rows are left at `RECEIVED` with no
chunks and nothing coming for them — the failure already seen in #52 and #53.

**This runbook therefore seeds and indexes in two separate startups, with a verification between
them.** Do not set both flags at once to save a recreation.

## Preconditions

1. The running backend image must contain the 15 in its packaged corpus — anything built from `main`
   at or after v1.2 does. Check what is deployed:

   ```bash
   cd /opt/maintenance-assistant
   docker compose -f docker-compose.prod.yml images backend
   ```

2. The provider key in `.env.prod` must be the live IONOS token, and the daily embedding budget must
   have room for one small batch.

3. A `.bak` of `.env.prod` before editing it, per the standing ops rule:

   ```bash
   cp .env.prod .env.prod.bak-$(date +%F)
   ```

**Remember that an edit to `.env.prod` is inert until the container is recreated.** `restart` is not
enough; every step below uses `up -d --force-recreate backend` for that reason.

## 1. Record the starting state

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U maintenance -d maintenance -c \
  "SELECT status, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY status" -c \
  "SELECT count(*) AS chunks FROM chunk" -c \
  "SELECT approval_state, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY approval_state"
```

Expected before anything happens: **150 protocols, all `INDEXED`, all `APPROVED`, 166 chunks.**

If the protocol count is not 150, stop and re-read this document — it assumes production has never
been seeded with the v1.2 batch.

## 2. Seed only — indexing stays off

```bash
# in .env.prod
CORPUS_SEED_ENABLED=true
INGESTION_BACKLOG_ON_STARTUP=false
```

```bash
docker compose -f docker-compose.prod.yml up -d --force-recreate backend
docker compose -f docker-compose.prod.yml logs --tail=50 backend | grep -i corpus
```

The loader logs what it inserted and what it skipped. **Nothing has been embedded yet and nothing
has been paid for.**

## 3. Verify the seed added only 15 rows

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U maintenance -d maintenance -c \
  "SELECT status, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY status"
```

**Required before continuing: 150 `INDEXED` and 15 `RECEIVED`.**

- `INDEXED` still exactly 150 → the originals were skipped, as intended.
- `RECEIVED` exactly 15 → the new batch is in and is waiting.

Anything else — 151 `INDEXED`, 14 `RECEIVED`, any `FAILED` — means the assumption behind this
runbook is wrong. **Stop and investigate; do not proceed to spend embedding calls.**

## 4. Index the 15

```bash
# in .env.prod
CORPUS_SEED_ENABLED=false
INGESTION_BACKLOG_ON_STARTUP=true
```

```bash
docker compose -f docker-compose.prod.yml up -d --force-recreate backend
docker compose -f docker-compose.prod.yml logs --tail=100 backend | grep -iE "backlog|index"
```

The startup line to look for names the count: `Startup backlog enqueued 15 protocols`.

Indexing then runs in the background on four workers. **Give it a minute** — the 150-protocol
corpus took 52 s, so 15 short protocols take a few seconds, and the wait here is mostly the
container coming up.

> The admin endpoint `POST /api/ingestion/backlog` does the same job without a restart, and is the
> better tool once you have an administrator token in hand. It is not used here because this realm
> deliberately refuses the password grant, so obtaining that token is its own procedure.

## 5. Verify the index

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U maintenance -d maintenance -c \
  "SELECT status, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY status" -c \
  "SELECT count(*) AS chunks FROM chunk" -c \
  "SELECT approval_state, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY approval_state"
```

Expected:

| | before | after |
|---|---|---|
| protocols, all `INDEXED` | 150 | **165** |
| chunks | 166 | **181** |
| `APPROVED` | 150 | 150 |
| `UNAPPROVED` | 0 | **15** |

`FAILED` must be 0. If anything is `FAILED`, see [step 9](#9-if-it-goes-wrong).

## 6. Cost, and what it should look like

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U maintenance -d maintenance -c \
  "SELECT * FROM embedding_budget ORDER BY usage_date DESC LIMIT 2"
```

The 15 protocols produce 15 chunks totalling 9,258 characters — **about 2,400 prompt tokens in a
single batched provider call**, since the batch size is 32.

| | 150-protocol corpus (measured, 2026-08) | these 15 (expected) |
|---|---|---|
| provider calls | 150 | **1** |
| prompt tokens | 27,713 | **~2,400** |
| cost | EUR 0.00055 | **~EUR 0.00005** |
| indexing time | 52 s | a few seconds |

**A much larger figure than that means something re-embedded the 150.** Stop and check step 3's
counts before doing anything else.

## 7. Verify the vectors are the provider's, not a stub's

This is the check that did not exist when the same 15 protocols were embedded by the e2e provider
stub into the development database and were unretrievable for a week (ADR-008). A vector's width,
norm and `status` column say nothing about which model wrote it.

```bash
docker compose -f docker-compose.prod.yml run --rm \
  -e MAINTENANCE_OPS_VERIFY_EMBEDDINGS=true backend
```

It re-embeds every stored chunk's own text and compares with the stored vector, prints a line per
chunk, and **exits 0 when clean, 1 when not**. Expected:

```
embedding provenance: 181 chunks checked, 0 foreign
```

Agreement is ~0.9999 for a vector written by the configured model and ≤0.04 for a foreign one, so
there is no judgement to make here. This costs one more scan of the corpus, about EUR 0.0006.

Any `FOREIGN` line means those protocols cannot be retrieved at all. Re-index them
([step 9](#9-if-it-goes-wrong)) rather than leaving them.

## 8. Verify retrieval, in a browser

Counts do not prove a technician can find anything. Sign in and ask, on **Presse 3 (PR-03)**:

> Presse reagiert beim Einschalten überhaupt nicht, nichts leuchtet

**Expected: a Mode A answer citing a v1.2 seed** — `Maschine startet nicht, keine Reaktion beim
Einschalten` or `presse aus, kein strom am pult`. Measured locally after the same operation: Mode A,
rank 1, similarity 0.6412.

A Mode B answer here means the vectors are wrong even if step 7 passed; do not accept the counts as
proof.

Then open **Protokollverwaltung** as `admin` and confirm the 15 appear as `UNAPPROVED` — that is the
approval queue this whole operation exists to fill.

## 9. If it goes wrong

**These 15 are deletable. The 150 originals are never to be touched.** The 15 carry ids beginning
`0f9c5b03-`; the originals begin `0f9c5b02-`.

- **Rows stuck at `RECEIVED` with no chunks** (the race, if the two startups were merged): re-run
  step 4. It is idempotent — indexing replaces a protocol's chunks rather than adding to them.
- **Rows at `FAILED`**: same, with `INGESTION_BACKLOG_ON_STARTUP=true`; the backlog picks up `FAILED`
  as well as `RECEIVED` when asked, and the log will say why they failed.
- **Foreign vectors reported by step 7**: set those protocols back and re-index. Only ever the 15:

  ```sql
  UPDATE protocol SET status = 'RECEIVED', indexed_at = NULL
  WHERE id::text LIKE '0f9c5b03%' AND deleted_at IS NULL;
  ```

  then repeat step 4. This is exactly the repair performed on the development database in PR #62.
- **Wrong or unwanted protocols arrived**: delete them through **Protokollverwaltung** as `admin`,
  which archives them properly through the ledger rather than by hand in SQL.
- **Anything worse**: the nightly `pg_dump` includes vectors and is the way back.

## 10. Leave the flags off

Both flags must end at `false`, so an unrelated restart never re-seeds or re-indexes anything:

```bash
# in .env.prod
CORPUS_SEED_ENABLED=false
INGESTION_BACKLOG_ON_STARTUP=false
```

```bash
docker compose -f docker-compose.prod.yml up -d --force-recreate backend
```

Confirm the service is healthy and the demo still answers. **This step is not optional**: leaving
`INGESTION_BACKLOG_ON_STARTUP=true` means every future deployment starts by scanning for work, and
leaving `CORPUS_SEED_ENABLED=true` means the seed file becomes an input to every restart.
