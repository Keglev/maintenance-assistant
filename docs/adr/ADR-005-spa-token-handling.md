# ADR-005: Keep the Public-Client SPA, Harden It, and Document the BFF

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-08 |
| **Deciders** | Project owner (solo) |
| **Related** | Follows [ADR-003](ADR-003-keycloak-for-iam.md) (Keycloak, public client + PKCE); implements NFR-3; headers in [`docker/caddy/Caddyfile`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/caddy/Caddyfile); realm in [`docker/keycloak/realm-export.json`](https://github.com/Keglev/maintenance-assistant/blob/main/docker/keycloak/realm-export.json) |

## Context

While measuring chat latency from the production host, the project owner copied his own access token
out of the browser and replayed it from `curl`. It worked, and it is supposed to work — that is what
a bearer token is. But it makes the question concrete: **anything that can read the tab's storage
can act as that user until the token expires.**

The application is an Angular single-page application configured as a Keycloak *public client*
(Authorization Code Flow + PKCE, no client secret, because a browser cannot keep one). The access
token therefore lives in the browser, and a decision is owed about how much that is worth defending
in a portfolio demo whose corpus is synthetic.

### The threat split, which is the whole of this decision

**(a) A compromised endpoint is out of scope — for this and for every web application.** If malware
runs on the user's machine, it can read cookies including `HttpOnly` ones from the browser's own
store, log the password as it is typed, screenshot the session, or drive the browser through its
debugging port. No web-application architecture defends against a compromised endpoint; a design
that claims to is mis-selling. Saying this plainly is more honest, and more useful in a review, than
adding a layer that does not address it.

**(b) Cross-site scripting reading the token from the SPA is in scope.** This is the realistic path
by which a *remote* attacker reaches the token, it has proportionate mitigations, and those
mitigations are cheap.

### What is already in place, and was verified rather than assumed

| Property | Value | How it was checked |
|---|---|---|
| Token storage | `sessionStorage` — tab-scoped, gone when the tab closes; **not** `localStorage` | `angular-oauth2-oidc` falls back to `sessionStorage` when no `OAuthStorage` is provided, and [`app.config.ts`](https://github.com/Keglev/maintenance-assistant/blob/main/frontend/src/app/app.config.ts) provides none. The only `localStorage` key in the application is the DE/EN language preference. |
| Access-token lifetime | 15 minutes | `"accessTokenLifespan": 900` in the realm export; matches the `expires_in` observed at login |
| Session lifetime | 30 min idle / 10 h maximum | `ssoSessionIdleTimeout` 1800, `ssoSessionMaxLifespan` 36000 |
| Flow | Authorization Code + PKCE (S256), no implicit flow, no direct access grants | `frontend` client in the realm export |
| Brute-force protection | on | `"bruteForceProtected": true` |
| DOM injection surface | none — no `innerHTML`, no `bypassSecurityTrust*` anywhere in `frontend/src`; the answer renderer splits the model into typed segments and Angular escapes every interpolation | grep, and the review of PR #25 |

The 15-minute lifetime is the quiet load-bearing control here: it is the ceiling on what a stolen
token is worth.

## Decision

**Keep the public-client SPA. Harden it at the edge. Document the BFF as the production-grade
evolution rather than building it.**

### 1. Security headers at the Caddy layer

Set on the application hostname, verified as landing on a real response (see *Consequences*):

| Header | Value | Why |
|---|---|---|
| `Content-Security-Policy` | `default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self' https://<auth host>; img-src 'self' data:; font-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self' https://<auth host>` | `script-src 'self'` is the line that matters: an injected inline script does not execute even if something manages to write one into the DOM. `connect-src` must name the Keycloak host — the discovery document and the token endpoint are fetched from there. |
| `X-Frame-Options` | `DENY` | The same statement as `frame-ancestors` for browsers that predate it |
| `X-Content-Type-Options` | `nosniff` | The backend already sent it; the SPA did not. Both halves of the origin now answer the same way. |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | A protocol id in a path must not leak to another site |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=()` | The application asks for none of them |
| `Strict-Transport-Security` | `max-age=31536000` | Both hostnames are HTTPS-only already. Deliberately **without** `includeSubDomains` and **without** `preload`: other subdomains of this domain belong to other projects and are not this deployment's to speak for. |
| `Server` | removed | Caddy's version is of no use to a visitor and of some use to someone matching exploits |

Keycloak's hostname keeps **its own** CSP. Replacing a policy written for an application this
deployment does not maintain is how a login page stops rendering; only headers Keycloak does not
already set are added there.

### 2. `style-src` keeps `'unsafe-inline'`, and that is a decision, not an oversight

Angular injects component styles as `<style>` elements at runtime — visible in the built bundle as
`createElement('style'); e.textContent = …`. The framework's only alternative is a per-request
nonce via `ngCspNonce`, and a statically served bundle behind a reverse proxy has no per-request
step in which to generate one. The residual risk is CSS injection (defacement, and in exotic setups
exfiltration of attribute values through selectors), not script execution. Buying `style-src 'self'`
would cost server-side rendering or a templating layer in front of `index.html`; that is a large
change to close a small gap.

The same build change that made `script-src 'self'` possible is worth recording: Angular's critical
CSS inlining emits `<link … onload="this.media='all'">`, an inline event handler, which the policy
refuses — leaving the stylesheet at `media="print"` and the application unstyled. Production builds
now set `optimization.styles.inlineCritical: false`, which emits a plain same-origin stylesheet link
and costs one small render-blocking request.

### 3. The BFF pattern is rejected for this project, and named as the enterprise answer

A backend-for-frontend holds the tokens server-side and gives the browser only an `HttpOnly`,
`Secure`, `SameSite` session cookie. It is the textbook answer to SPA token theft and the right
answer in an enterprise setting, where sessions are long, data is real and an XSS bug is a breach
rather than an incident.

It is rejected **here** because of what it costs relative to what it buys in this system: a stateful
session store, CSRF protection on every mutating request (the cookie is sent automatically, which is
exactly the property that makes CSRF possible again), a second authorization-code exchange path, and
one more service on a two-vCPU host — to protect a token that expires in fifteen minutes and grants
read access to a synthetic corpus of invented machine faults. The demo users hold nothing worth
stealing, by construction.

### 4. Recorded, not fixed

- **Refresh-token rotation is not configured.** The realm leaves `revokeRefreshToken` at its default
  (off), so a refresh token can be reused within the session lifetime. The SPA does not use silent
  refresh (`useSilentRefresh: false`) and never calls `refreshToken()`, so in practice the session
  ends when the access token expires and the user signs in again. Turning rotation on would be a
  realm change with no code change; it is listed here so the next reader knows it is a choice and
  not an oversight.
- **No token binding, no DPoP.** Sender-constrained tokens would defeat the replay demonstrated at
  the top of this record. They are not supported end-to-end by this stack without work that would
  dwarf the demo.

## Consequences

**Positive**

- The realistic remote path to the token — XSS — is closed at the browser rather than argued about:
  no inline script executes, no third-party origin can be contacted, and nothing may frame the app.
- The headers cost nothing at runtime and nothing in the build, and they are the first thing a
  security-minded reviewer checks with `curl -I`.
- The honest threat split is defensible in a conversation. "We do not defend against a compromised
  endpoint, and here is why no web app can" is a stronger answer than a layer that implies otherwise.
- The CSP was verified to land on a real 200 response, and to **override** an upstream value: with a
  stub upstream sending `X-Content-Type-Options: sniff-me-please`, the proxied response still carried
  `nosniff`. Caddy's `defer` is what makes that true, and it was measured rather than trusted.

**Negative**

- `style-src 'unsafe-inline'` remains. The policy is therefore not the strictest one a scanner will
  ask for, and the reason has to be explained every time it is asked.
- The Caddyfile is not deployed by CI. Like `docker-compose.prod.yml`, it reaches the host only when
  a person copies it there — so this hardening is inert until someone does (arc42 §7.4, §7.5).
- A CSP is a live risk to a working demo. Any future addition of a third-party origin, an inline
  script or an iframe fails closed, in the browser, and possibly silently in a way `curl` never sees.
- Rejecting the BFF means the token remains readable by any script that runs in the page. The
  mitigation is that none should be able to run; the residual risk is that an Angular escaping bug or
  a careless future `innerHTML` would change that in one commit.

## Alternatives considered

- **Backend-for-frontend with an `HttpOnly` session cookie** — the correct enterprise answer; costs a
  stateful session layer, CSRF handling and a second auth path to protect a 15-minute token over a
  synthetic corpus. *Rejected for this project, documented as the production-grade evolution.*
- **Move the token to an in-memory variable only (no `sessionStorage`)** — genuinely narrows the
  window, since XSS must then run while the tab is open rather than read storage at leisure. But it
  loses the session across a page reload, which for an OIDC redirect flow means a round trip to
  Keycloak on every refresh; and the same injected script that could read storage can also read the
  variable it replaces. *Rejected: real cost, small gain.*
- **Shorten the access token below 15 minutes** — cheap, and it does reduce the value of a stolen
  token. At five minutes a technician mid-question would be interrupted three times an hour without
  silent refresh, and adding silent refresh means a hidden iframe against Keycloak — more surface,
  not less. *Rejected; 15 minutes is the balance point for this demo.*
- **`style-src 'self'` with `ngCspNonce`** — the strictest policy Angular supports. Requires a
  per-request nonce injected into `index.html`, which means server-side rendering or templating in
  front of a currently static bundle. *Rejected: closes CSS injection at the price of the deployment
  model.*
- **Ship the policy as `Content-Security-Policy-Report-Only` first** — the textbook safe rollout.
  Without a report collector it produces console violations only, which is exactly what the manual
  browser walkthrough already surfaces. *Rejected as ceremony for a one-host demo; the walkthrough is
  the report.*
