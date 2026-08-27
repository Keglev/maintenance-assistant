# ADR-007: Test the rendered application in a browser

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-11 |
| **Deciders** | Carlos Keglevich |
| **Related** | ADR-001 (modular monolith), ADR-002 (LLM provider, latency), ADR-006 (insider threat, moderation), NFR-2 (grounded answers), NFR-4 (latency), PR #26, PR #47 |

## Context

Every robot report in this project ends with the same line: *"NOT verified: no browser was opened."*
That line is not a formality. It marks the exact gap through which the two most expensive defects of
v1.0–v1.1 reached production, and both were found by a person looking at a screen — the only
instrument the project had.

**#26 — the citation links that answered 401.** Source links were rendered as
`<a href="/api/protocols/{id}/document">`. A browser-followed href is a fresh navigation: it never
reaches Angular's HTTP interceptor, so it carries no Bearer token, and the backend is a stateless
JWT resource server with no cookie fallback. Every click on a citation failed in production. The
verification that had been run was `curl` with a token, which answered 200 — a true statement about
a request nobody makes. **An API call is not a click.**

**#47 — the login heading nobody could see.** The realm heading above the login card rendered white
on a near-white canvas: measured 1.09:1, which is not "low contrast", it is invisible. Every
code-level check passed honestly, because the element was **absent from the theme's stylesheet**. It
inherited a colour the parent theme had chosen for a dark photographic backdrop that our theme
removes. Reading a stylesheet cannot find a rule that is not in it. Worse, the dark scheme was
correct *by accident*, so the one palette anyone happened to check looked fine.

The existing stack cannot see either. vitest runs in **jsdom**: no layout engine, no cascade worth
trusting, no navigation, no notion of what is painted behind what. It is excellent at what it does —
218 specs in seconds — and it is structurally incapable of answering "does a click work" or "can a
human read this".

The v1.2 approve workflow makes the gap worse. It adds a multi-role chain in which the author, the
corrector and the approver are three different people, and its correctness is *a sequence of
navigations by different roles*. Building that on top of a test stack that cannot navigate would be
building the most state-dependent feature in the project blind. Carlos reordered the roadmap on
2026-08-11 for exactly this reason: **the safety net comes before the feature it catches.**

## Decision

**Adopt Playwright as a second, small, rendered test suite, and give it a job description narrow
enough that it stays small.**

**What E2E covers — flows and rendering:**

- Whether a **flow completes**: sign in, ask, click a citation, correct a protocol, archive it.
- Whether the result is **visible and legible**: computed colour against computed background, in
  both palettes.
- Whether **routing and role gating** behave for a real navigation, including a typed URL.
- Whether a request the **browser performs itself** carries what it needs.

**What stays in vitest — logic:** mode routing, role filtering, citation segment parsing, the clamp
arithmetic, dictionary lookups, guards as functions. These run in seconds against no stack. **A test
that can be written in jsdom must be written in jsdom.** E2E is expensive — it wants a database, a
Keycloak and a backend — and a suite that grows into a full regression net becomes the thing people
disable.

**Environment: the local development stack.** Postgres and Keycloak from `docker/docker-compose.yml`,
the backend from Maven, and the Angular dev server started by Playwright itself.

**Never production, enforced structurally.** The base URL and the Keycloak URL are validated against
an allow-list of loopback hostnames, in a module imported at the top of the Playwright config — so a
misconfigured run throws while the config is being read, before a browser exists. An allow-list, not
a deny-list of known production hosts: a deny-list fails open for every host nobody thought of. There
is no override flag.

**Anything created is removed through the application, with a reason — never SQL.** The moderation
round trip archives its own protocol as its final assertion, and a stray artifact from a crashed run
is swept by the next run through the same audited path.

**No LLM in the critical path.** `POST /api/query` is stubbed at the network layer with a canned
Mode A answer; everything else in that test stays real. The one assertion that genuinely needs a
model — "the answer changes after a re-index" — lives in its own file and is skipped unless
`E2E_LLM=1`.

**Chromium only.** The users are on shop-floor tablets and desktop Chrome and Edge; all three are
Chromium.

**No sleeps.** Every wait is a condition. Where a wait cannot be expressed as one, that is stated
rather than padded with a timeout.

## Consequences

**Positive**

- The two defect classes that reached production are now **assertions that run**, one of them
  checking the status the browser actually received rather than the status curl receives.
- The release drill Carlos performs by hand — file, correct, archive, verify the ledger — runs in
  about ten seconds and is no longer dependent on someone remembering all six steps.
- The suite found **two real defects in existing code on its first complete run** (see below), which
  is the argument for it, made by itself.
- v1.2's approve workflow gets a net before it is built, in the one dimension it is most likely to
  break: a multi-role sequence of navigations.

**Negative**

- **A green E2E run is still not a production check, and must never be read as one.** It runs
  against a dev-mode Keycloak, a dev server rather than the built bundle behind nginx, no Caddy, no
  CSP headers, no TLS, and a corpus seeded locally. Production has broken while every local check
  passed — that is the whole history above. Carlos's browser drill is not replaced by this and is
  not scheduled to be.
- **It needs a stack**, so it cannot run on a bare CI runner without one being provisioned. Today it
  therefore does not gate merges (see below).
- **The local database is not pristine between runs.** Archived rows and moderation events
  accumulate by design; the suite is written to depend on nothing global.
- **A second test vocabulary** for contributors to learn, and a second place where a change to a
  `data-testid` can break something.
- **It will be slower than it looks today.** Twenty tests in 45 s is comfortable; the discipline
  about what belongs here exists so that it stays that way.

**Defects found on the first complete run** — reported, not fixed, and parked on the v1.1.1 list:

1. `.footer-note` measures **3.65:1** in the light palette (`--c-ink-faint` on the light surface),
   below AA. The dark palette is fine — the #47 shape exactly.
2. The protocol viewer **never moves focus into itself**. Focus stays on the link that opened it, so
   the backdrop never receives the Escape keydown and Tab is not trapped: a keyboard user cannot
   dismiss the dialog with the key every modal has taught them to press. `dialog.ts` is written to do
   all three things and has **no unit spec at all** — the clearest possible illustration of this
   ADR's argument.

Both are recorded as `test.fail()` rather than skipped, so they are measured on every run and turn
the build red the day they are fixed.

## Alternatives considered

- **Keep verifying by hand.** It is what found both defects, and it does not scale to a release
  cadence or to a multi-role approval chain. It also does not run on a pull request. Kept as a
  complement, rejected as the only instrument. *Rejected as sufficient.*
- **Cypress instead of Playwright.** Comparable for this job. Playwright wins on three specifics
  this project needs: first-class `colorScheme` emulation (the contrast tests are a matrix over
  light and dark), real cross-origin navigation without ceremony (the Keycloak round trip is a
  different origin, historically Cypress's weak point), and network interception that leaves the
  rest of the request path real. *Rejected.*
- **A disposable compose stack brought up per run.** Repeatable and isolated, and it would require
  inventing a **second deployment topology**: the compose file's application service has been
  commented out since Phase 1, so this is not a flag but a new artifact to keep in step with the
  real one forever. Two stacks that drift is a worse failure than a dirty database, because the
  suite would go green against a system nobody ships. *Rejected.*
- **Run the real LLM in the citation test.** More end-to-end in the literal sense, and it buys
  nothing for the defect under test while adding cost, a 38.9 s measured worst case and
  non-deterministic prose to every run. *Rejected, except behind `E2E_LLM=1` where the model's
  output is genuinely the thing being asserted.*
- **Seed a token into `sessionStorage` and skip the login page.** Faster on every test, and it would
  skip the one flow that has already broken in production. *Rejected.*
- **Make E2E a required check now.** It cannot pass without a provisioned stack, so making it
  required today would block every merge. Revisited below. *Rejected for now.*

## CI status, stated rather than implied

The E2E job runs on pull requests touching the frontend, **as a separate job under its own name**,
and it is **not** part of the required `build-and-test` check. A failing E2E run does **not** block a
merge today.

That is a deliberate, temporary position and it is written here so nobody has to infer it from a
workflow file. It is honest for exactly one reason: the job provisions Postgres, Keycloak and the
backend inside CI, and until that has proven stable over a number of real pull requests, a flaky
infrastructure step would train people to ignore a red check — which is worse than not having one.

**Recommendation: make it required once it has run green on ten consecutive pull requests without an
infrastructure-caused failure.** The tests it contains are exactly the ones whose absence cost
production defects; leaving them advisory permanently would repeat the mistake in a new form.

---

## Dated note — 2026-08-12: the gaps from #50, closed

#50 shipped this suite with an honest list of what it had not proven. This is what happened when
those were worked through, and none of it changes the decision above.

### 1. The re-index test ran, and it had four defects of its own

`reindex.e2e.ts` asserts the one behaviour Carlos hand-drills before every release: an edited
protocol changes the answer that cites it. It had **never executed** — it is skipped without a model
— and running it once, against a real IONOS key, produced a passing test only after four fixes:

| What was wrong | Why nobody knew |
|---|---|
| It waited for the status text `INDEXIERT`. The interface renders **"Durchsuchbar"** | The string never existed; the test could not have passed in any state of the application |
| Its inner ceilings (120 s, 240 s) exceeded the **60 s test timeout** | The polls they guarded were unreachable |
| It selected the search machine picker **by value**; that picker's values are UUIDs | Inside a `toPass` retry loop the failure is invisible — the poll never gets past its first line |
| It had **no cleanup sweep**, so a run that failed part-way left an INDEXED protocol behind | Seven accumulated, and the eighth run then failed because a previous run's corrected protocol was being retrieved as corpus |

Then it passed, in 33 s. **The evidence, same question and same machine:**

> **Before** — *"Die Linie stoppt nach dem Etikettierer. [P1] Ursache ist eine verschmutzte
> Lichtschranke. [P1]"*
>
> **After** — *"Die Linie stoppt nach dem Etikettierer. [P1] Ursache war ein gerissener Zahnriemen
> am Etikettierer. [P1]"*

**The lesson generalises past this file.** A skipped test is not a neutral placeholder — it is
untested code with a green tick beside it, and it decays exactly like any other unexecuted code. Two
of those four defects were introduced by the same hand that wrote the passing tests around them, on
the same day. The suite's own rule follows: **a test that cannot run in CI is a test that will be
wrong when it finally does.**

### 2. The provider is stubbed, so the test runs everywhere

`frontend/e2e/provider-stub/` is an OpenAI-compatible server of about 200 lines and no dependencies.
The backend is pointed at it with `LLM_BASE_URL` — a configuration value the application already
supports **because ADR-002 requires the provider to be swappable**. No production code changed.

**Faked:** the two paid HTTP calls. **Real:** the upload, the chunker, the pgvector write, the status
transition, retrieval, the threshold, citation validation, the re-index's delete-then-write, and
every byte of the rendered answer. The chat stub is a **parrot** — it answers by quoting the sources
out of the prompt the backend built — so the answer changes when, and only when, the retrieved text
changes. That is the property under test; a stub returning a fixed string would pass whether or not
re-indexing worked.

One honest consequence: **the similarity threshold is a property of the embedding model**, and
`application.yml` already says so ("bge-m3-specific: any embedding-model change invalidates it").
The stub is a different embedding model, so the stubbed deployment gets its own value. Measured on
the stub's vectors: **0.42–0.45** for a question against the protocol it is about, **0.10–0.13**
against an unrelated one. `0.30` sits in that gap with margin on both sides, so the Mode A / Mode B
distinction survives intact rather than being switched off.

### 3. CI gets a database per run; a laptop does not

The local dirt accepted above is still accepted, and for the reasons given. **CI is different in one
way that matters: a reused database there makes a green run depend on the order the runs happened
in.** That is not hypothetical — it is exactly what the seven leftovers did locally. The CI job now
creates its own Postgres, migrates it, seeds and indexes the corpus, and destroys it with the
runner. Cost: about **35 s** of the run, for a database no second run can ever see.

The seed also revealed that CI had been running with an **unindexed corpus**:
`INGESTION_BACKLOG_ON_STARTUP` defaults to false, so 150 protocols were being seeded as `RECEIVED`
with zero chunks. Nothing had needed a chunk until `reindex.e2e.ts`. Armed now, and free, because
the provider is a stub.

### 4. The suite's first caught-and-closed defect — the argument, demonstrated

#50 reported that the protocol viewer never took focus, so Escape could not close it and Tab was not
trapped. The cause, found here: `focusables()` matched `button` **without excluding disabled ones**,
and the viewer's first projected control is a Download button that is disabled until the document
arrives. `focus()` on a disabled element does nothing and reports nothing.

Fixed — disabled controls excluded, the panel itself (`tabindex="-1"`) as the fallback target — with
the **unit spec the component had never had**. `src/app/shared/dialog/` had a template, a stylesheet
and a class, and no test at all, while its own comments promised "Esc closes, Tab cycles inside, the
first control takes focus on open". None of the three was true for one of its four callers.

**Both halves of the stack were needed, and neither would have done.** jsdom reproduces the defect
exactly — it refuses focus to disabled elements as a browser does — so the eight new unit tests run
in two seconds on every commit and go red for the right reason. But nothing in jsdom would ever have
*pointed* at the viewer: the defect only appears when a real dialog opens with a real disabled
button, which is a rendered condition. The browser found it; the unit spec now holds it.

The `test.fail()` marker in `citation.e2e.ts` is deleted and that assertion is a plain passing test —
which is what such a marker is for. **The contrast marker stays**: `--c-ink-faint` is shared with the
tagline, the eyebrow, `.optional` and disabled text, so it is a design pass with its own drill and it
remains on the v1.1.1 list.

### 5. Still advisory, and the count so far

**4 consecutive green runs** of the e2e job (three on #50, one on main after merge). The bar in this
ADR is ten.

**It stays advisory, and this PR does not move it — for a reason stronger than the count.** The job
those four runs exercised is not the job that runs now: it has gained a throwaway database, a
provider stub, an armed indexing backlog and the re-index test it had always skipped. **A streak
counts runs of the same thing.** The count restarts here, and the running total is kept in
`frontend/e2e/README.md`, where someone deciding whether to promote the check will actually look.

---

## Dated note — 2026-08-13: visual regression

**Ten baselines, five surfaces, both palettes, one viewport (1280×900), compared on every pull
request.** A separate check from the functional suite.

### Why, in numbers rather than principle

v1.1 spent **four pull requests** — #41, #44, #45, #46 — on spacing and layout defects. Every one was
found the same way: a person opened production and looked. #45's source list lost its top margin only
when a toggle rendered between two elements; #46's header row sat welded to the list beneath it;
#47 shipped a heading at 1.09:1 that every code-level check passed.

None of that is visible to 226 unit tests or 21 functional e2e tests, and not because those tests are
bad. **They assert what the DOM says. A layout defect is a statement about what the page looks
like**, and the only instrument that can answer it is a picture.

### What it covers, and the sentence that must travel with it

It covers the surfaces that have already broken: a Mode A answer, a clamped long answer with its
drawer toggle and source header (three defects came from that one area), the Verwaltung table, the
upload view's two-column text mode, and the Keycloak login page.

**What it cannot do: a baseline records what the application LOOKS LIKE, not what it should look
like.** A screenshot of an ugly layout is a perfectly valid baseline and will hold that ugliness in
place indefinitely. These tests prove that pixels *changed*; a human decides whether they changed for
the better. Two consequences follow, and both are enforced in the README rather than left to taste:

1. **Regeneration is an explicit act** — `npm run e2e:visual:update`, never a side effect of a normal
   run.
2. **New baselines belong in the same pull request as the design change that caused them.** A
   follow-up "fix the baselines" commit turns the record into a rubber stamp applied after the fact,
   and means the reviewer of the design change never saw the pixels it moved.

**And it says nothing whatsoever about production.** These pictures are of a dev server, in Chromium,
at one viewport, against a locally seeded corpus. Production serves a built bundle through nginx
behind Caddy with a strict CSP. A green visual run means the layout did not change *here*.

### The stability constraint, which decides the whole design

**Font rendering is a property of the machine.** The same page screenshotted on Windows and on a
GitHub runner differs on nearly every glyph edge. A baseline generated on a developer's desktop is
therefore a permanently red CI job — and a permanently red job is one people learn to ignore, which
is worse than not having the check at all.

So there is exactly one authority: **the pinned `mcr.microsoft.com/playwright:v1.56.0-noble`
container**, used both to generate baselines locally and to compare them in CI, which runs Playwright
inside that same image via `docker run --network host`. `snapshotPathTemplate` drops Playwright's
per-OS suffix so the repository cannot quietly accumulate a second, unchecked set. The image tag is
pinned beside the `@playwright/test` version and the two are bumped together.

On Docker Desktop, `--network host` is the VM's network rather than the developer's, so a small TCP
bridge forwards loopback into the container. It exists so that **the production guard stays exactly
as strict as it is** — pointing the tests at `host.docker.internal` would have been two lines shorter
and would have traded a safety property for a screenshot.

### Tolerance

`maxDiffPixelRatio: 0.002` — two pixels per thousand, about 2,400 at this viewport. Chosen from what
each end costs: at zero, antialiasing on one glyph edge reddens the job and the check becomes noise;
at 0.01 the allowance is a whole component moved, and #46's defect was a few hundred pixels of white
space. Proven by measurement rather than argument — reverting #46's one-line fix produced a diff of
**50,629 pixels (ratio 0.05)**, twenty-five times the threshold, on four of the ten baselines.

### A separate check, not a step in the existing job

A pixel diff and a broken flow are different news for different people, and they are acted on
differently: "the source list moved four pixels" wants a designer, "the citation click 401s" stops
everything. Folded together, one amber word would hide the other signal — and the usual reaction to a
noisy visual check is to disable the job it lives in, which would take the functional tests with it.

Cost: one more stack provision, about two minutes. The stack setup itself is now a **shared composite
action** used by both jobs rather than copied into each, because #51 closed two defects that were the
same bug twice, both caused by a helper being duplicated instead of extracted.

**Both checks remain advisory.** The `visual` job starts its own count at 0 against the ten-run bar.

### Recorded, not taken

More viewports (tablet portrait — the primary device — and 1920px, where #44's defects lived), more
browsers, and per-component baselines instead of full pages. Each multiplies the number of pictures a
reviewer must judge and the ways a run can go red. One viewport and five surfaces is a size a person
will actually look at, which is the property that matters most in a check whose output is a picture.

### What the first visual run caught — a CI defect wearing a layout disguise

The check paid for itself before its baselines were a day old, and not in the way expected.

Its first CI run reported a regression on the Verwaltung table: 54,577 pixels, 7 px taller than the
baseline. The diff artifact settled it in seconds — **the rows were identical**, and the only
difference was the status column, reading "Accepted, indexing" where the baseline read "Searchable".
Not a layout regression at all. The corpus had not finished indexing when the screenshot was taken.

Underneath were **two real defects in the CI stack**, both shipped in #51 and both invisible until
something cared about a pixel:

1. **The indexing wait could not tell "finished" from "not started."** It waited for *no protocol in
   RECEIVED*, which is equally true of an empty database, so it sailed through before the seeder had
   inserted a row. It then ran out of iterations without failing, letting the job continue against a
   half-built fixture. The condition is now positive — 150 INDEXED — and the step fails loudly.
2. **The seed and the indexer race.** `CORPUS_SEED_ENABLED` and `INGESTION_BACKLOG_ON_STARTUP` both
   act at startup and nothing orders them; when the backlog scan wins it finds an empty table,
   enqueues nothing and never runs again. Every functional run since #51 had been a coin toss on
   whether the corpus had chunks at all — and passed regardless, because no assertion until these
   baselines depended on it. Seeding and indexing are now separate starts with the row count
   asserted in between.

**This is the argument for a visual check, made by the check itself, in its first hours.** A picture
asserts everything in frame, including the things nobody thought to assert — and the value here was
not that the layout was wrong, but that the *fixture* was, silently, in a way four other test suites
had been tolerating.

## Revision 2026-08-27 — the pinned image is now v1.62.1-noble

The sentence under "one authority" above still names the tag that was pinned when this record was
accepted, and it is left as written: a decision record is corrected by a dated note rather than by
an edit to its text.

**Revision 2026-08-27 — the pinned image is now v1.62.1-noble (#124); the tag moves with
`@playwright/test`, the two pins in `frontend-e2e-visual.yml:85` and `run-visual-docker.mjs:25` are
the authority, this sentence is left as written.**

The decision is unchanged — one image, one authority — and only the tag moved. Two things measured
on the way are worth keeping:

- **All eighteen baselines passed unchanged** under 1.62.1 and its newer Chromium. The upgrade
  crossed six minor versions and moved no pixel, which is the first evidence this project has that
  a Playwright bump is not automatically a baseline regeneration.
- **The local half of "generate locally, compare in CI" has no working path today.** The image would
  not pull on the maintainer's machine for the second time (DECISIONS.txt, 2026-08-27), so the
  regeneration in #124 could not have been run locally had it been needed, and the pass was measured
  on the runner instead. That is a WATCH against this ADR's mechanism, not a change to it.
