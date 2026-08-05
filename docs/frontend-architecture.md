# Frontend Architecture

The Angular client is deliberately thin: it authenticates, it shows what the caller's role permits,
and it renders what the backend returns. It holds no authorization logic of its own.

> **Phase 1 state.** The walking skeleton covers login and one protected page. The search and upload
> views arrive in Phase 3; this page grows with them.

## Shape

Angular 22 with standalone components — no NgModules. Routes are lazy-loaded, so the login page does
not pay for the code the home page needs:

| Route | Purpose |
|---|---|
| `/login` | Triggers the redirect to Keycloak. Explicit click rather than automatic redirect, because an automatic one turns a transient Keycloak error into a loop the user cannot escape. |
| `/home` | Behind the auth guard. Shows the username and realm roles the token carries, and calls `/api/hello` to prove the backend accepted the same token. |

State that outlives a component lives in signals on an injectable service; there is no store library
and no `zone.js` change-detection dependency to reason about.

## Authentication

Authorization Code Flow with PKCE via `angular-oauth2-oidc`, against the public client `frontend` in
the Keycloak realm `maintenance` ([ADR-003](adr/ADR-003-keycloak-for-iam.html)). The browser is a
public client that cannot keep a secret, which is exactly what PKCE exists for.

`AuthService` wraps the OIDC library so components never touch it directly. Discovery and redirect
completion run in an application initializer, so a login returning from Keycloak finishes before the
first route renders — otherwise the guard would see "not authenticated" mid-flight. Realm roles are
read from the **access token** (`realm_access.roles`), not the ID token, because that is where
Keycloak puts them.

**The guard is convenience, not security.** It keeps an anonymous visitor off a page that would fail
anyway; the backend resource server enforces the same roles on every request, and that is the only
place an authorization decision counts (NFR-3). Hiding a control is not enforcing it.

## Talking to the backend

The access token is attached only to calls to this application's own API — never to third-party
hosts. In development the dev server proxies `/api/*` to the backend on port 8080, so the browser
sees one origin and the backend needs no CORS configuration. In production both sit behind the same
reverse proxy, which is why the API base URL is a relative path.

## Build and quality

Vitest in jsdom for unit tests, with coverage published to the
[frontend coverage report](/maintenance-assistant/frontend/coverage/index.html). Compodoc generates
the [API reference](/maintenance-assistant/frontend/api-docs/index.html) from the TypeScript
sources. The production image is
nginx serving the built bundle, with `try_files` so a reload of `/home` returns the application
rather than a 404.
