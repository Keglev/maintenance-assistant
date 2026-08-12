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
