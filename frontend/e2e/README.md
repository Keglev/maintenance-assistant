# End-to-end tests

Playwright, in Chromium, against a **real application, a real Keycloak and a real backend**.

This is the rendered half of the test stack. The other half — 218 vitest specs in jsdom — is faster,
cheaper and covers far more; see
[ADR-007](../../docs/adr/ADR-007-end-to-end-testing-strategy.md) for what
belongs where and why this exists at all.

```bash
npm run e2e          # headless
npm run e2e:headed   # watch it happen
npm run e2e:report   # the HTML report of the last run
```

## What it needs running

The suite starts **only the Angular dev server** (Playwright's `webServer`, reusing one you already
have). Everything below it is the stack a developer already runs:

| Piece | How | Port |
|---|---|---|
| PostgreSQL + pgvector | `docker compose -f docker/docker-compose.yml up -d postgres` | 5433 (`docker/.env`) |
| Keycloak, realm `maintenance`, themed | `docker compose -f docker/docker-compose.yml up -d keycloak` | 8081 |
| Backend | `cd backend && mvn spring-boot:run` | 8080 |
| Frontend | started by Playwright | 4200 |

If any of it is missing the run stops on the **first** test with a sentence naming what is
unreachable, rather than thirty seconds of timeouts per test — that is what the `stack` fixture in
`support.ts` is for.

**No LLM key is required — for anything, including `reindex.e2e.ts`.** That test needs an embedding
and an answer, not necessarily a *paid* one: point the backend at the stub in
[`provider-stub/`](provider-stub/server.mjs) and run the whole suite for free.

```bash
# one terminal
node e2e/provider-stub/server.mjs --port 8099

# the backend, against the stub — see the stub's header for what is and is not faked
LLM_BASE_URL=http://127.0.0.1:8099/v1 LLM_API_KEY=stub \
INGESTION_BACKLOG_ON_STARTUP=true CORPUS_SEED_ENABLED=true \
QUERY_SIMILARITY_THRESHOLD=0.30 mvn spring-boot:run

# and the suite, with the re-index test switched on
E2E_LLM=1 npm run e2e
```

Against a **real** provider instead, set `LLM_API_KEY` and leave `LLM_BASE_URL` alone. Both were
exercised; the re-index test passes in ~33 s against IONOS and ~11 s against the stub.

> **The threshold override is not a cheat.** `application.yml` records that 0.55 is *bge-m3-specific*
> and that any embedding-model change invalidates it. The stub is a different embedding model, so it
> gets its own calibration — measured at 0.42–0.45 for a relevant question and 0.10–0.13 for an
> irrelevant one, which leaves 0.30 comfortably inside the gap. The Mode A / Mode B distinction is
> preserved, not disabled.

## The environment decision

**The local dev stack, not a disposable one brought up per run.** Both were on the table; this is
why the dev stack won.

- **The compose file has no application service.** It is commented out and has been since Phase 1 —
  a developer runs the backend from an IDE or Maven. A "disposable stack" would therefore not be a
  flag on an existing file, it would be a **second deployment topology** invented for the tests,
  which then has to be kept in step with the real one forever. Two stacks that drift is a worse
  failure than a slow test: the suite would go green against a system nobody ships.
- **The seeded realm and the seeded corpus already exist here.** The E-47 demo case, the four demo
  users, the themed login page — the fixtures this suite needs are the fixtures the project already
  maintains, and they are maintained because the demo depends on them.
- **It is the stack the developer is already looking at.** A test that fails in the same environment
  someone can immediately open in a browser is a test that gets fixed.

What we give up, stated plainly: **the database is not pristine between runs.** That is why cleanup
is a rule rather than a convenience — see below — and why nothing in the suite asserts a global
count of anything.

## Visual regression

Twelve baselines — six surfaces × two palettes — compared per run. They exist because v1.1 spent
**four pull requests** (#41, #44, #45, #46) on spacing and layout defects that were all found the
same way: Carlos opened production and looked.

The sixth surface, added in v1.2, is the Mode A answer with an **unapproved source**. It is the one
baseline chosen for something other than churn, and the reason is that being seen *is* the feature:
an unapproved source a technician does not notice is the 2026-08-11 decision failing silently. It
carries a token pair (`--c-review-*`) that appears nowhere else, an amber chip inside a green block
and a line above the answer text — three things a functional test can assert the existence of and
none of which it can notice going pale, colliding, or vanishing behind a fade.

```bash
npm run e2e:visual          # compare against the baselines
npm run e2e:visual:update   # regenerate them — a deliberate act, never automatic
```

Both run **inside `mcr.microsoft.com/playwright:v1.56.0-noble`**, and that is not optional.

### Baselines have exactly one home

**Font rendering is a property of the machine.** The same page screenshotted on Windows and on a
GitHub runner differs on nearly every glyph edge, so a baseline generated on a desktop is a
permanently red CI job — and a permanently red job is one people learn to ignore, which is worse
than not having it.

So there is **one authority**: the pinned Playwright container. It is what `npm run e2e:visual*`
uses locally and what the CI job runs (`docker run --network host …` — the same image, the same
Chromium build, the same fonts). `snapshotPathTemplate` deliberately drops Playwright's per-OS
suffix, so the repository cannot accumulate a `-win32` set nobody checks.

The image tag is pinned next to the `@playwright/test` version in two places (`package.json` and
`e2e/run-visual-docker.mjs`). **Bump them together, never one** — a mismatched Chromium is a
whole-suite diff.

> On Docker Desktop (Windows/macOS) `--network host` is the *VM's* network, not yours, so
> `e2e/host-bridge.mjs` forwards loopback into it. On Linux and in CI it is a no-op. The bridge
> exists so the production guard stays exactly as strict as it is — the tests really do talk to
> loopback.

**The dev server runs on YOUR machine, not in the container** — the pinned image ships Node 22.20
and the Angular CLI wants 22.22 or newer, so a dev server started inside it exits before it serves
anything. Only the *browser* has to live in the pinned image; the thing it renders can be served
from anywhere. The CI job does the same (see `frontend-e2e-visual.yml`).

> On **Windows**, start it as `npm start -- --host 0.0.0.0`. `ng serve` otherwise binds to the IPv6
> loopback only, and the bridge reaches the host over `host.docker.internal`, which is IPv4 — so the
> container finds nothing, Playwright decides it has to start its own server, and the run dies on
> the Node version above. The error names the Node version and not the binding, which is why this
> note exists.

### When a baseline fails: regression, or did you mean it?

1. **Download the diffs.** The CI job uploads them as the `visual-diffs` artifact on failure.
   Locally they are in `frontend/test-results/<test-name>/`.
2. **Open the three PNGs** Playwright writes per failed baseline: `…-expected.png` (committed),
   `…-actual.png` (this run), `…-diff.png` (the pixels that moved, in magenta). The diff answers the
   question in about ten seconds.
3. **Decide.**
   - *You did not change the design* → **regression**. The diff is the bug report; fix the code.
   - *You did change the design* → **intended**. Regenerate: `npm run e2e:visual:update`, look at
     the new PNGs before staging them, and commit them.

> **Check the dev server actually rebuilt before you regenerate.** `e2e:visual:update` photographs
> whatever the dev server is serving, and after a failed rebuild that is the *last good bundle* —
> Vite keeps serving it rather than nothing, and the container has no way to tell. Baselines taken
> then record the layout you have already replaced, and the local compare run afterwards passes,
> because it compares that bundle against baselines made from it. It took a red CI job to notice.
> Look for `Application bundle generation complete` in the dev server's output first.

**Regeneration belongs in the SAME pull request as the change that caused it.** A separate "fix the
baselines" commit is the one workflow this suite must never acquire: it turns a record of what the
application looks like into a rubber stamp applied after the fact, and it means the reviewer of the
design change never saw the pixels it changed. If you are regenerating baselines and cannot point at
the change in the same diff that caused them, something is wrong.

**A baseline records what the application LOOKS LIKE — not what it should look like.** A screenshot
of an ugly layout is a valid baseline and will hold that ugliness in place forever. These tests
prove pixels *changed*; a human still decides whether they changed for the better.

### What is masked, and why

| Masked | Reason |
|---|---|
| `.num` (upload/archive dates, pager state) | The corpus is seeded at container start, so "uploaded at" is when the runner booted. The pager state carries a count that grows with every writing test. |
| `[data-testid="health-dot"]` | Polls a live backend on a 60 s interval — green, amber or grey depending on when the shot was taken. |
| `.source-meta` (similarity %, incident date) | A float from a vector search. Stable today; a reranker or a re-embed would move it, and that is a retrieval change, not a layout regression. |
| `[data-testid="archive-row"] td` | Deletion timestamps. |

An unmasked clock makes a baseline that fails tomorrow morning for no reason at all.

## Is the CI check blocking yet?

**No. It is advisory: a red `e2e` job does not stop a merge.** ADR-007 sets the bar for promoting it
to a required check at **ten consecutive green runs with no infrastructure-caused failure**.

| | |
|---|---|
| **Consecutive green runs of the CURRENT `e2e` job** | **2** — the count restarted on 2026-08-12 |
| Green runs of the previous job shape | 4 (three on #50, one on main after merge) |
| Why the count restarted | That job had no throwaway database, no provider stub, an unindexed corpus and skipped the re-index test. A streak counts runs of the same thing. |
| **`visual` job** | Separate check, also advisory. **2 consecutive green runs** on the same baselines (2026-08-13) |

The `visual` job is deliberately a **separate check**: a pixel diff and a broken flow are different
news for different people, and a red `visual` beside a green `e2e` says exactly what it means — it
works, and it looks different. Folding them together would also mean the usual reaction to a noisy
visual check (disable the job) takes the functional tests with it.

**Update this table when the job changes shape or the streak advances.** It lives here rather than
only in the ADR because this is the file someone opens when they wonder why a red check did not stop
them.

## CI differs from a laptop in exactly one way

**CI gets a throwaway database per run**; a laptop keeps its own. That is not an inconsistency, it is
the one place where the trade-off flips: a developer's stack is long-lived and the suite cleans up
after itself, while on a build server a reused database would make a green run depend on the order
the runs happened in. See ADR-007 for the full argument, and the sweep rule below for what keeps the
local case honest.

## Rules this suite obeys

**1. Loopback only, enforced before anything starts.** `guard.ts` is imported at the top of
`playwright.config.ts`, so a non-loopback `E2E_BASE_URL` or `E2E_KEYCLOAK_URL` throws while the
config is being read — before a browser is launched. It is an allow-list of three loopback names,
not a deny-list of production hosts: a deny-list fails open on every host nobody thought of, and the
one it fails open on is the one someone typed by mistake. **There is deliberately no override flag.**

```
$ E2E_BASE_URL=https://maintenance.smartsupply.com.de npm run e2e
ProductionGuardError: REFUSING TO RUN: E2E_BASE_URL points at
"maintenance.smartsupply.com.de", which is not loopback.
```

**2. Anything created is removed through the application, with a reason.** Never SQL. The moderation
round trip archives its own protocol as its final assertion, and a stray artifact from a crashed run
is swept by the next run's `afterEach` — by the same audited path. A cleanup that reached into the
database would bypass exactly the ledger the test exists to prove, and would still pass if that
ledger were broken.

> **One documented exception, and it is arrangement rather than verification.**
> `correctAsSchichtleiter` in `support.ts` performs a correction as an authenticated `PUT` from the
> signed-in Schichtleiter's own browser instead of by clicking. It exists because since 2026-08-13
> only a Schichtleiter may correct, and `/moderation` — the only view with a Bearbeiten button — is
> guarded by `roleGuard('admin')`: **the role that owns the act cannot reach the screen that
> performs it.** That is a reported routing finding for Carlos to decide, not something a UI pull
> request widened on its own. Every assertion around the call is still in the browser, and the
> helper is written to be **deleted** the day the route opens.

What that leaves behind is real and is accepted: an archived row and a `moderation_event` per run.
ADR-006 has no restore, by design. They are capped at 50 per machine with the oldest purged, the
machine used is `VP-01` rather than the E-47 demo machine on `PR-03`, and every artifact is titled
`E2E-THROWAWAY <timestamp>`.

**3. No sleeps. Wait on state, not on time.** Every wait in this suite is a condition: an element
becoming visible, a button becoming enabled, a status reaching `INDEXIERT`. Indexing is asynchronous
*by design* (the upload answers 202 and a pipeline runs), so `reindex.e2e.ts` polls with
`expect.poll`-style retries and a stated ceiling instead of guessing a duration. A fixed sleep is a
guess about a machine's speed, and the flake it produces lands on whoever runs the suite next.

**4. Known defects are `test.fail()`, never `test.skip()`.** Two tests here document real defects in
existing code (see the PR and the v1.1.1 list in PROJECT-PHASES). They **run** on every CI run, they
measure the real value, and they turn the build **red the day someone fixes the underlying issue** —
which is the signal to delete them. A skip would rot silently.

## Why the query is stubbed and the document is not

`citation.e2e.ts` intercepts `POST /api/query` and returns a canned Mode A answer. Everything the
#26 defect actually lived in stays real: the real app, the real token, the real interceptor, the
real backend, the real file on disk. Only the model's prose is canned.

That is not a shortcut, it is the point. A real query is a paid call to a shared-capacity provider
with a measured 38.9 s worst case (ADR-002) whose output is non-deterministic text. Putting it in
the critical path of a regression test would add cost and flakiness and would prove nothing extra
about a defect that was never about the answer.

## Files

| File | Covers |
|---|---|
| `login.e2e.ts` | the themed Keycloak page (asserted by theme-owned elements, because a broken theme falls back to stock **silently**), demo sign-in, a refused password, sign-out |
| `citation.e2e.ts` | **#26**: a citation click fetches its document with a token and does not 401, asserted on the status the browser received |
| `role-gating.e2e.ts` | **#38**: the admin lands in Protokollverwaltung; shop-floor roles cannot reach `/moderation` by URL either |
| `moderation.e2e.ts` | **the release drill**: file → correct with a reason → archive with a reason → still on the record |
| `approval.e2e.ts` | **the v1.2 trust chain**: the queue without a machine, an approval that names who and when, a correction that resets it, a withdrawal that takes a reason, and the approved-only facet asserted on what the browser sent |
| `contrast.e2e.ts` | **#47**: computed colour against computed background, both palettes, AA |
| `reindex.e2e.ts` | the answer changing after a re-index. **Needs a real LLM key; `E2E_LLM=1`** |

## How the three test kinds stay apart

| Runner | Directory | Suffix | Config |
|---|---|---|---|
| Angular build | `src/` | — | `tsconfig.app.json` |
| vitest (`npm test`) | `src/` | `.spec.ts` | `tsconfig.spec.json` |
| Playwright (`npm run e2e`) | `e2e/` | `.e2e.ts` | `tsconfig.e2e.json` |

Enforced twice on purpose — the directories do not overlap and neither do the suffixes, with
Playwright's `testMatch` naming `.e2e.ts` explicitly. vitest could not collect an e2e file even if
one were moved into `src/`, and Playwright could not collect a unit spec moved into `e2e/`. The
`types` differ too, because `expect` means something different in each.
