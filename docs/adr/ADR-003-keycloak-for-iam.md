# ADR-003: Keycloak as Identity Provider

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-05 |
| **Deciders** | Project owner (solo) |
| **Related** | Implements NFR-3; realm configuration in [`docker/keycloak/realm-export.json`](../../docker/keycloak/realm-export.json) |

## Context

Four roles (Operator, Techniker, Schichtleiter, Admin) require role-based page access **and**
role-based answer filtering (NFR-3), both enforced server-side. Authentication must follow current
standards; credential storage and the login UI should not be custom-built.

**Note on a common phrasing confusion:** "Why Keycloak instead of OAuth2?" is a category error —
OAuth2/OIDC are *protocols*, Keycloak is a *product that implements them*. The real question is:
who runs the authorization server that issues tokens? Options: (a) an IdP product like Keycloak,
(b) build one with Spring Authorization Server, (c) a cloud IdP (Auth0, Entra ID, AWS Cognito).

## Decision

Keycloak (containerized, part of docker-compose) as OIDC provider, realm `maintenance`. The
Angular frontend uses Authorization Code Flow + PKCE through the public client `frontend`; the
Spring Boot backend acts as an OAuth2 Resource Server validating Keycloak-issued JWTs; Keycloak
realm roles map to Spring Security authorities.

The realm configuration is exported and versioned (`realm-export.json`) and imported on container
startup, so `docker compose up` reproduces users, roles and clients deterministically.

## Consequences

**Positive**

- No custom login forms, no password storage, standard flows (OIDC, PKCE) — "don't roll your own
  auth."
- The admin user story (US-6: manage users and roles without code changes) comes for free via the
  Keycloak admin console.
- Keycloak is the de-facto standard self-hosted IdP in German enterprise (open source, Red
  Hat/CNCF ecosystem) — directly job-relevant.
- Self-hosted is consistent with the data-residency story (no user data at a US cloud IdP).

**Negative**

- Heaviest of the three containers (~1 GB RAM) and realm configuration has a learning curve
  (budgeted: 2–3 days).
- Realm config must be exported and versioned so the stack stays reproducible; a manual change made
  in the admin console and not re-exported is lost on the next fresh start.

## Alternatives considered

- **Spring Authorization Server (build the IdP in-app)** — educational, but reimplements user
  management, login UI and token issuance that Keycloak provides hardened; also merges IdP and app
  into one deployable, which is not how enterprises do it. *Rejected.*
- **Cloud IdP (Auth0/Cognito/Entra)** — less ops, but user data leaves the self-hosted EU stack,
  weakening the project's core claim; adds an external account dependency for anyone running the
  demo. *Rejected.*
- **Custom JWT issuance ("manual OAuth2/JWT")** — maximum control, maximum risk; security-sensitive
  code a portfolio should explicitly avoid hand-rolling. *Rejected.*
