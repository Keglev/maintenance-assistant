# Runbook — bring the 15 v1.2 protocols into production

**Status: EXECUTED 2026-08-19.** It ran against production and succeeded — 165 protocols, 181
chunks, 15 `UNAPPROVED`, provenance scan clean. The observed outcome is recorded at the end of this
document.

**It succeeded only because the executor deviated from the written text in four places.** As
originally written this runbook took production down at step 2. All four corrections are applied
below; the [record](#record-of-the-2026-08-19-execution) says what they were.

**Who runs this:** Carlos, on the server, at `/opt/maintenance-assistant`.

**Time:** about 20 minutes, measured — most of it waiting for three container recreations.
**Cost:** **EUR 0.000046** measured for the indexing, plus about EUR 0.0006 if you run the
provenance scan (see [step 6](#6-cost-and-what-it-should-look-like)).

> This document is deliberately not published to the documentation site. It is for the person
> holding SSH, the same way the Keycloak theme runbook lives beside the theme.

## READ THIS BEFORE THE FIRST COMMAND

**Every `docker compose` command below carries `--env-file .env.prod`, and none of them works
without it.** Compose auto-loads only a file named literally `.env`; this deployment's file is
`.env.prod`, so without the flag every variable is empty and
`up -d --force-recreate backend` recreates the backend with a **blank database password, blank
hostnames and no provider key**. That is an outage, not a warning.

**If any compose command prints lines like this, STOP:**

```
level=warning msg="The \"POSTGRES_PASSWORD\" variable is not set. Defaulting to a blank string."
level=warning msg="The \"APP_DOMAIN\" variable is not set. Defaulting to a blank string."
```

The env file is not being read. Do not run the next command — check the `--env-file` flag and the
directory you are in first. **Reading that warning block is a step, not a habit**: on 2026-08-19 it
is the only thing that stood between this runbook and a broken production.

The CI deploy workflow has always done this correctly; compare
`.github/workflows/backend-deploy.yml`, which builds
`COMPOSE="docker compose -f …/docker-compose.prod.yml --env-file …/.env.prod"`.

## Why

v1.2 added 15 easier-fault protocols so that the approval queue has something real in it. **They
shipped as code and never as data.** Production held the 150 originals, every one of them
`APPROVED` by `system:corpus-seed`, so:

- **the approve queue was empty** and the v1.2 trust chain — the thing v1.2 exists to demonstrate —
  could not be shown by clicking;
- questions like *"Presse reagiert beim Einschalten überhaupt nicht"* had no protocol to find.

Getting these 15 in is what makes v1.2 visible. It is not a data top-up.

## What this must not do

**The 150 original protocols must not be re-embedded, re-written or touched.** They are correct,
they cost money to produce, and re-embedding them would spend that money again for no change.

**This is safe by construction, not by care:** the seed loader inserts with the protocol's own fixed
UUID and `ON CONFLICT (id) DO NOTHING`, so a row that already exists is skipped by the database
rather than by a decision in the loader. Indexing then only ever selects protocols in status
`RECEIVED`; the 150 are `INDEXED` and are not selectable. Both guarantees are checked in
[step 3](#3-verify-the-seed-added-only-15-rows) before any embedding is paid for.

Confirmed on 2026-08-19: the loader reported `15 rows inserted, 150 already present`.

## The race this is ordered around

`CorpusSeedRunner` and `IngestionBacklogRunner` are both `ApplicationRunner` beans and **neither
declares an order**, so with both flags set in one startup the backlog can run *before* the seed has
inserted anything. It then finds nothing to do, and the 15 rows are left at `RECEIVED` with no
chunks and nothing coming for them — the failure already seen in #52 and #53.

**This runbook therefore seeds and indexes in two separate startups, with a verification between
them.** Do not set both flags at once to save a recreation.

## Preconditions

1. The running backend image must contain the 15 in its packaged corpus — anything built from `main`
   at or after v1.2 does. The provenance check in [step 8](#8-verify-the-vectors-are-the-providers-not-a-stubs)
   additionally needs the image to carry PR #62, i.e. commit `909a4ff` or later. Check what is
   deployed:

   ```bash
   cd /opt/maintenance-assistant
   docker compose -f docker-compose.prod.yml --env-file .env.prod images backend
   ```

2. The provider key in `.env.prod` must be the live IONOS token, and the daily embedding budget must
   have room for one small batch.

3. A `.bak` of `.env.prod` before editing it, per the standing ops rule:

   ```bash
   cp .env.prod .env.prod.bak-$(date +%F)
   ```

**Remember that an edit to `.env.prod` is inert until the container is recreated.** `restart` is not
enough; every step below uses `up -d --force-recreate backend` for that reason.

## Setting the two flags — do not reach for `sed` alone

**The flags may or may not already exist in `.env.prod`, and the two cases need different
commands.**

- **Before the 2026-08-19 run they were absent entirely**, and the application fell back to the
  `false` defaults in `application.yml`. A plain
  `sed -i 's/^CORPUS_SEED_ENABLED=false/CORPUS_SEED_ENABLED=true/' .env.prod` matches nothing,
  **exits 0, and changes nothing** — the OPS-RULE-3 silent-no-op, the same shape as the `mv` that
  once "applied" a Caddyfile without applying it.
- **After that run both lines exist** (set to `false`), so an edit is now the right operation.

Use this helper, which is correct in both cases. Paste it once per session; the steps below call it.

```bash
set_flag() {                       # usage: set_flag KEY value
  local key="$1" val="$2"
  if grep -qE "^${key}=" .env.prod; then
    sed -i "s|^${key}=.*|${key}=${val}|" .env.prod
  else
    printf '%s=%s\n' "$key" "$val" >> .env.prod
  fi
}
```

**And verify after every change, because the whole point is that a failed edit is silent:**

```bash
grep -nE '^(CORPUS_SEED_ENABLED|INGESTION_BACKLOG_ON_STARTUP)=' .env.prod
```

Two lines must come back, with the values you just set. No output means the write did not happen.

## 1. Record the starting state

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  psql -U maintenance -d maintenance \
  -c "SELECT status, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY status" \
  -c "SELECT count(*) AS chunks FROM chunk" \
  -c "SELECT approval_state, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY approval_state"
```

Expected on a production that has never had this batch: **150 protocols, all `INDEXED`, all
`APPROVED`, 166 chunks.** Observed exactly that on 2026-08-19.

If the protocol count is not 150, stop and re-read this document — it assumes production has never
been seeded with the v1.2 batch. **After the 2026-08-19 run the expected starting state is the
finishing state below (165 / 181 / 15 `UNAPPROVED`), and there is nothing left to do.**

## 2. Seed only — indexing stays off

```bash
set_flag CORPUS_SEED_ENABLED true
set_flag INGESTION_BACKLOG_ON_STARTUP false
grep -nE '^(CORPUS_SEED_ENABLED|INGESTION_BACKLOG_ON_STARTUP)=' .env.prod
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate backend
docker compose -f docker-compose.prod.yml --env-file .env.prod logs --tail=50 backend | grep -i corpus
```

The loader logs what it inserted and what it skipped. **Nothing has been embedded yet and nothing
has been paid for.** The 2026-08-19 line read:

```
Corpus seed finished: 165 records read, 15 rows inserted, 150 already present,
15 documents written, 150 already on disk
```

## 3. Verify the seed added only 15 rows

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  psql -U maintenance -d maintenance \
  -c "SELECT status, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY status" \
  -c "SELECT count(*) AS chunks FROM chunk"
```

**Required before continuing: 150 `INDEXED`, 15 `RECEIVED`, and chunks still 166.**

- `INDEXED` still exactly 150 → the originals were skipped, as intended.
- `RECEIVED` exactly 15 → the new batch is in and is waiting.
- chunks unchanged → nothing has been embedded, nothing has been billed.

Anything else — 151 `INDEXED`, 14 `RECEIVED`, any `FAILED` — means the assumption behind this
runbook is wrong. **Stop and investigate; do not proceed to spend embedding calls.**

## 4. Index the 15

```bash
set_flag CORPUS_SEED_ENABLED false
set_flag INGESTION_BACKLOG_ON_STARTUP true
grep -nE '^(CORPUS_SEED_ENABLED|INGESTION_BACKLOG_ON_STARTUP)=' .env.prod
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate backend
docker compose -f docker-compose.prod.yml --env-file .env.prod logs --tail=100 backend \
  | grep -iE "backlog|index"
```

The startup line to look for names the count: `Startup backlog enqueued 15 protocols`.

Indexing then runs in the background on four workers, and takes seconds. Wait for the queue to
drain rather than guessing:

```bash
until docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  psql -U maintenance -d maintenance -tAc \
  "SELECT count(*) FROM protocol WHERE status = 'RECEIVED' AND deleted_at IS NULL" \
  | grep -q '^0$'; do sleep 4; done; echo "all RECEIVED cleared"
```

> The admin endpoint `POST /api/ingestion/backlog` does the same job without a restart, and is the
> better tool once you have an administrator token in hand. It is not used here because this realm
> deliberately refuses the password grant, so obtaining that token is its own procedure.

## 5. Verify the index

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  psql -U maintenance -d maintenance \
  -c "SELECT status, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY status" \
  -c "SELECT count(*) AS chunks FROM chunk" \
  -c "SELECT approval_state, count(*) FROM protocol WHERE deleted_at IS NULL GROUP BY approval_state"
```

Expected, and all four observed on 2026-08-19:

| | before | after |
|---|---|---|
| protocols, all `INDEXED` | 150 | **165** |
| chunks | 166 | **181** |
| `APPROVED` | 150 | 150 |
| `UNAPPROVED` | 0 | **15** |

`FAILED` must be 0. If anything is `FAILED`, see [step 10](#10-if-it-goes-wrong).

## 6. Cost, and what it should look like

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  psql -U maintenance -d maintenance -c "SELECT * FROM embedding_budget ORDER BY usage_date DESC LIMIT 2"
```

**Measured on 2026-08-19**, against the predicted figures this runbook originally carried:

| | 150-protocol corpus (2026-08) | these 15 — predicted | these 15 — **measured** |
|---|---|---|---|
| provider calls | 150 | 1 | **15** |
| prompt tokens | 27,713 | ~2,400 | **2,322** |
| cost | EUR 0.00055 | ~EUR 0.00005 | **EUR 0.000046** |
| indexing time | 52 s | a few seconds | a few seconds |

**The call count was predicted wrong and the reason is worth carrying: the indexer embeds PER
PROTOCOL.** Each protocol's chunk list is one `embed()` call, and the batch size of 32 applies
*within* that list, never across protocols. Fifteen protocols of one chunk each are therefore
fifteen calls, not one batched call. The token count — which is what is actually billed — was
within 3% of the estimate.

**A much larger figure than that means something re-embedded the 150.** The two are not close:
15 calls / 2,322 tokens against 150 calls / 27,713 tokens. Stop and check step 3's counts before
doing anything else.

## 7. Turn both flags off

**This happens before the verification steps, not after them.** Two reasons, both learned on
2026-08-19: the state you verify should be the state production keeps, and the ops container in
step 8 reads the same env file — with the backlog flag still armed it starts a backlog pass of its
own. Harmless when there is nothing to index, and not a thing to leave armed while running a
diagnostic.

```bash
set_flag CORPUS_SEED_ENABLED false
set_flag INGESTION_BACKLOG_ON_STARTUP false
grep -nE '^(CORPUS_SEED_ENABLED|INGESTION_BACKLOG_ON_STARTUP)=' .env.prod
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate backend
```

**This step is not optional**: leaving `INGESTION_BACKLOG_ON_STARTUP=true` means every future
deployment starts by scanning for work, and leaving `CORPUS_SEED_ENABLED=true` means the seed file
becomes an input to every restart.

Confirm the restart did neither — this must print `0`:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs --tail=60 backend \
  | grep -icE "corpus seed finished|backlog enqueued"
```

## 8. Verify the vectors are the provider's, not a stub's

This is the check that did not exist when the same 15 protocols were embedded by the e2e provider
stub into the development database and were unretrievable for a week (ADR-008). A vector's width,
norm and `status` column say nothing about which model wrote it.

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod run --rm \
  -e MAINTENANCE_OPS_VERIFY_EMBEDDINGS=true backend
```

It re-embeds every stored chunk's own text and compares with the stored vector, prints a line per
chunk, and **exits 0 when clean, 1 when not**. Observed on 2026-08-19:

```
embedding provenance: 181 chunks checked, 0 foreign
```

Agreement is ~0.9999 for a vector written by the configured model and ≤0.04 for a foreign one, so
there is no judgement to make here. This costs one more scan of the corpus, about EUR 0.0006 —
estimated from the corpus token count, not measured separately on the day.

Any `FOREIGN` line means those protocols cannot be retrieved at all. Re-index them
([step 10](#10-if-it-goes-wrong)) rather than leaving them.

## 9. Verify retrieval, in a browser

Counts do not prove a technician can find anything. Sign in and ask, on **Presse 3 (PR-03)**:

> Presse reagiert beim Einschalten überhaupt nicht, nichts leuchtet

**Expected: a Mode A answer citing a v1.2 seed.** On 2026-08-19 it cited **both** —
`Maschine startet nicht, keine Reaktion beim Einschalten` and `presse aus, kein strom am pult` —
with the answer-level line *"2 der Quellen sind noch nicht freigegeben — geprüft hat sie noch
niemand"* and the sources badged **Nicht freigegeben**. That is v1.2's approval visibility working
on production data.

A Mode B answer here means the vectors are wrong even if step 8 passed; do not accept the counts as
proof.

Then open **Protokollverwaltung** as `admin` and confirm the 15 appear as `UNAPPROVED` — that is the
approval queue this whole operation exists to fill.

**Finally, the standing rule: the demo must always work.** Ask the E-47 question on PR-03
(*"Presse kommt nicht auf Druck, Fehler E-47, was tun?"*) and confirm it still answers Mode A citing
the four E-47 protocols, all `Freigegeben`. It did on 2026-08-19 — which is the check that the 150
originals were not disturbed.

## 10. If it goes wrong

**These 15 are deletable. The 150 originals are never to be touched.** The 15 carry ids beginning
`0f9c5b03-`; the originals begin `0f9c5b02-`.

- **Rows stuck at `RECEIVED` with no chunks** (the race, if the two startups were merged): re-run
  step 4. It is idempotent — indexing replaces a protocol's chunks rather than adding to them.
- **Rows at `FAILED`**: same, with `INGESTION_BACKLOG_ON_STARTUP=true`; the backlog picks up `FAILED`
  as well as `RECEIVED` when asked, and the log will say why they failed.
- **Foreign vectors reported by step 8**: set those protocols back and re-index. Only ever the 15:

  ```sql
  UPDATE protocol SET status = 'RECEIVED', indexed_at = NULL
  WHERE id::text LIKE '0f9c5b03%' AND deleted_at IS NULL;
  ```

  then repeat step 4. This is exactly the repair performed on the development database in PR #62.
- **Wrong or unwanted protocols arrived**: delete them through **Protokollverwaltung** as `admin`,
  which archives them properly through the ledger rather than by hand in SQL.
- **A recreate that came up with blank configuration**: you omitted `--env-file .env.prod`. Restore
  from the `.bak` if the file itself was touched, re-run the command with the flag, and re-read the
  warning block at the top of this document.
- **Anything worse**: the nightly `pg_dump` includes vectors and is the way back.

---

## Record of the 2026-08-19 execution

Run against production by an AI session on Carlos's explicit instruction, which is a **one-time
override** of the standing rule that robots do not touch production — see PROJECT-PHASES. The rule
is unchanged for everything else.

| step | gate | result |
|---|---|---|
| 1 | starting state | 150 `INDEXED`, 166 chunks, 150 `APPROVED`, 0 `UNAPPROVED` — as predicted |
| 2 | seed only | `165 records read, 15 rows inserted, 150 already present` |
| 3 | gate before spending | 150 `INDEXED` + 15 `RECEIVED`, chunks still 166 |
| 4 | index | `Startup backlog enqueued 15 protocols` |
| 5 | verify | 165 `INDEXED`, 181 chunks, 150 `APPROVED` / 15 `UNAPPROVED`, 0 `FAILED` |
| 6 | cost | 15 calls, 2,322 prompt tokens, EUR 0.000046 |
| 7 | flags off | both `false`; clean restart triggered neither seed nor backlog |
| 8 | provenance | `181 chunks checked, 0 foreign`, exit 0 |
| 9 | browser | Mode A citing both v1.2 seeds; admin queue populated; E-47 demo intact; 0 CSP violations |

**Every count and the token estimate were right. Four things in the text were wrong**, and the run
succeeded only because the executor departed from it:

1. **No compose command carried `--env-file .env.prod`.** As written, step 2 would have recreated
   the backend with a blank database password, blank hostnames and no provider key. Caught only
   because the preconditions command emitted the `variable is not set` warning block — which the
   document did not tell anyone to read. It does now, at the top.
2. **The two flags did not exist in `.env.prod`**, so they had to be appended; the document
   described editing them, and a `sed` edit would have exited 0 without changing anything.
3. **The cost table said one provider call.** It is 15 — the indexer embeds per protocol.
4. **The provenance and browser checks ran before the flags were turned off**, so they verified an
   intermediate state and the ops container inherited an armed backlog flag.

The document's own precondition step is what prevented the outage, by printing warnings the
executor read. That is now a written step rather than an assumed habit, which is the practice worth
carrying: **a runbook that produces diagnostic noise must say which noise is a stop signal.**
