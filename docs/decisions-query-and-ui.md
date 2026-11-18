# Query path and UI decisions

Part of the [product decisions](decisions.md).

## Answering a question

- **Chat call: plain RestClient**, for the same proportion reason as embeddings. There is no
  conversation to remember (every question is answered from the retrieved chunks alone), no tool
  to call, and citation enforcement has to be checked against this query's retrieved chunks, which
  no framework can know.
- **Similarity threshold 0.55,** measured against the real corpus through the production path.
  The sweep is flat from 0.48 to 0.55, so moving it inside the plateau changes nothing observable.
  The floor is measured and named so nobody optimises toward it: below 0.48 a Mode B question
  acquires a citation. The value is bge-m3-specific; any change to chunking, corpus or embedding
  model means re-measuring it first, starting with the cross-language cases, which clear it by the
  smallest margins. See [ADR-008](adr/ADR-008-retrieval-measurement.md) and
  [ADR-009](adr/ADR-009-hybrid-retrieval.md).
- **One chat model for every role,** Llama-3.3-70B-Instruct. Qwen3.5-9B was measured worse on the
  production prompt where it matters: it failed to ground the cross-language case and fragmented
  answers into many more claims. It stays a configuration swap.
- **Two prompts, not one with a flag.** The citation few-shot cannot reach the Mode B path because
  it is not in that file, and Mode B's schema has no source field, so a leaked citation is a shape
  the provider will not emit. Mode B carries a shape example; without one, constrained decoding
  emitted the opening brace and then spent the whole output budget on whitespace.
- **Citations are per protocol, not per chunk:** the chunk is the search unit, the protocol the
  citation unit. Labelling per chunk cited one protocol twice as if it were two pieces of evidence.
- **Three layers enforce the citation rule,** and only the last does not depend on the model: a
  few-shot example, a JSON schema in which an uncited claim cannot be represented, and validation
  against the chunks this query retrieved. A Mode A answer whose claims all fail validation falls
  through to Mode B.
- **The output cap is 2100 tokens,** runaway protection sized for the documented fallback model,
  and a truncated grounded answer is reported as a degradation, not an outage.
- **Query guards (NFR-7):** 10 questions per minute per user, keyed by the token's subject, in
  memory; a daily chat budget in its own table, separate from the embedding budget, so a re-index
  storm cannot exhaust the day's answers; the output cap set in the client so no prompt can forget
  it; an answer cache keyed by normalised question, machine and role, checked before the budget. The
  role is in the cache key because an operator served a technician's answer would get repair steps,
  a safety bug invisible in testing.
- **The role comes from the token, never from the request:** it decides what the answer may
  contain.
- **Latency:** variance comes from provider queueing, not from any network path (identical
  requests, identical token counts, twice the time minutes apart). NFR-4 is met at the median and
  at the worst observed; a slow outlier on a bad queue day stays possible on shared serverless
  capacity with no SLA, and the mitigations are the client timeout, the "still working" state and
  the output cap.

## Retrieval quality

- **The golden question set is ratified by the owner** (all expectations ruled KEEP in one act), so
  the metrics rest on expectations the measuring agent did not write and grade itself. The harness
  requires the set to be ratified: a question added later is caught as a proposal before the
  aggregates can quote it.
- Two standing conditions live in the data: G16 flips to a Mode A case in the same change that
  seeds Einlagerung protocols, and G19 stays single-answer, because "bleibt stehen" (was running)
  and "geht nicht an" (never started) are different machine states.
- The set measures direction, not quality, and can still be over-fitted to.
- **The help panel is part of retrieval's contract with the reader,** so a change to
  `LexicalTerms` is a change to the panel: a term needs at least one letter and one digit to be
  matched literally, which is why "E-47" and "SV0410" work alone and "frei" does not. Two specs pin
  both halves.
- **Query completeness-gating is rejected and not re-proposed:** "e-47" is a lazy query that works,
  and Mode B already handles the harmless ones.

## Interface

- **The two answer modes are two visually distinct blocks,** a requirement, not styling (NFR-2):
  Mode A green with a check and inline [P1] markers linking into a source list; Mode B amber with a
  hatched top edge, its badge, and no source area at all, because an empty "Quellen" heading invites
  the reader to assume sources are merely missing. Badge text, border weight and icon carry the
  difference as well as hue, so it survives monochrome and colour blindness.
- **The client renders the answer and does not re-read it:** the backend already validated every
  citation; a marker with no matching citation stays plain text.
- **DE/EN is a runtime dictionary,** not build-time i18n: two builds and a routing decision would
  make the language a property of the URL rather than of the person, and switching would lose the
  answer on screen. A key missing from one dictionary is a compile error.
- **UI language is independent of answer language:** an English interface still shows a German
  answer, and the answer element carries its own `lang` attribute.
- **The upload view is honest about the 202:** a status list with the failure reason, refreshed by
  a button, no polling loop.
- **Protocols open in an in-app viewer,** fetched with the token and rendered as typed segments,
  never as HTML; anything unrecognisable falls back to the raw text, so the dialog is never blank.
- **Role gating in the UI is presentation, never protection** (NFR-3): a control is hidden from a
  role that would get a 403, and the backend refuses regardless.
- **Contrast is measured on every consumer of a token before the token moves.** The faint-ink token
  failed AA on five light surfaces, not one, and one value change fixed all five; inactive controls
  keep their own lower-contrast token, which WCAG exempts.
- **Parked permanently:** the dark locale select on the Keycloak page uses PatternFly's grey. It
  passes AA and the file is hand-deployed; reopen only alongside a theme change that is deployed
  anyway.

## Browser security and token handling ([ADR-005](adr/ADR-005-spa-token-handling.md))

- **The threat split is the decision:** malware on the user's machine is out of scope for every web
  application; XSS reading the token out of the SPA is in scope.
- **The public client stays:** access token in sessionStorage (tab-scoped), 15-minute lifetime, Code
  Flow with PKCE S256, no implicit or password grants. The lifetime is the ceiling on what a stolen
  token is worth.
- **A backend-for-frontend is the enterprise answer and is rejected here:** a session store, CSRF
  handling and one more service on a small host, to protect a 15-minute token over a synthetic
  corpus. Documented as the production-grade evolution.
- **Security headers live at Caddy,** one place for the SPA, the API and the OpenAPI UI: CSP,
  X-Frame-Options, nosniff, Referrer-Policy, Permissions-Policy, HSTS without includeSubDomains.
  Keycloak's hostname keeps its own CSP.
- **style-src keeps 'unsafe-inline':** Angular injects component styles at runtime, and a
  per-request nonce cannot come from a statically served bundle. Angular's critical-CSS inlining is
  off in production builds, because its inline `onload` handler breaks a strict CSP.
- **Recorded, not fixed:** refresh-token rotation is off and the SPA does not refresh, so a session
  simply ends; no token binding or DPoP.
- **The Caddyfile deploys by hand,** like the compose file ([ops rules](architecture/07a-ops-rules.html)).

[Back to the product decisions](decisions.md)
