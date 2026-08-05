# ADR-003: Keycloak as Identity Provider

**Status:** Accepted · **Date:** 2026-08-05

## Context
Four roles (Operator, Techniker, Schichtleiter, Admin) with role-based page access AND role-based answer filtering (NFR-3), enforced server-side. Authentication must follow current standards; credential storage and login UI should not be custom-built.

**Note on a common phrasing confusion:** "Why Keycloak instead of OAuth2?" is a category error — OAuth2/OIDC are *protocols*, Keycloak is a *product that implements them*. The real question is: who runs the authorization server that issues tokens? Options: (a) an IdP product like Keycloak, (b) build one with Spring Authorization Server, (c) a cloud IdP (Auth0, Entra ID, AWS Cognito).

## Decision
Keycloak (containerized, part of docker-compose) as OIDC provider. Flows: Angular frontend uses Authorization Code Flow + PKCE; Spring Boot backend acts as OAuth2 Resource Server validating Keycloak-issued JWTs; Keycloak realm roles map to Spring Security authorities.

## Consequences
- (+) No custom login forms, no password storage, standard flows (OIDC, PKCE) — "don't roll your own auth."
- (+) Admin user story (US-6: manage users/roles without code changes) comes for free via Keycloak admin console.
- (+) Keycloak is the de-facto standard self-hosted IdP in German enterprise (open source, Red Hat/CNCF ecosystem) — directly job-relevant.
- (+) Self-hosted = consistent with the data-residency story (no user data at a US cloud IdP).
- (−) Heaviest of the three containers (~1 GB RAM) and realm configuration has a learning curve (budgeted: 2–3 days).
- (−) Realm config must be exported and versioned (realm-export.json) so `docker compose up` reproduces it.

## Alternatives rejected
- **Spring Authorization Server (build the IdP in-app):** educational, but reimplements user management, login UI, and token issuance that Keycloak provides hardened; also merges IdP and app into one deployable, which is not how enterprises do it.
- **Cloud IdP (Auth0/Cognito/Entra):** less ops, but user data leaves the self-hosted EU stack, weakening the project's core claim; adds an external account dependency for anyone running the demo.
- **Custom JWT issuance ("manual OAuth2/JWT"):** maximum control, maximum risk; security-sensitive code a portfolio should explicitly avoid hand-rolling — the ADR itself demonstrates that judgment.
