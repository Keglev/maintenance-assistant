/**
 * Web module — the HTTP boundary of the application.
 *
 * <p>Responsibility: REST endpoints, request validation, error handling, OpenAPI documentation,
 * and the security configuration. The application is an OAuth2 Resource Server: it validates
 * Keycloak-issued JWTs offline against the realm's JWKS and maps realm roles to Spring Security
 * authorities (ADR-003). It never issues tokens and holds no login form.
 *
 * <p>Authorization decisions are made here and in {@code query}, always server-side. The Angular
 * client hides what a role may not do, but hiding is not enforcing (NFR-3).
 */
package com.keglevich.maintenanceassistant.web;
