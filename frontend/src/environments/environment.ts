/**
 * Production/default environment.
 *
 * The values are placeholders until the Hetzner deployment exists (Phase 4); the shape is what
 * matters, so application code never needs to know which environment it runs in.
 */
export const environment = {
  production: true,

  /** Keycloak realm base URL — the OIDC issuer (ADR-003). */
  keycloakIssuer: 'https://auth.example.org/realms/maintenance',

  /** Public client from the versioned realm export; Auth Code Flow + PKCE, holds no secret. */
  keycloakClientId: 'frontend',

  /** Backend base URL. Same-origin in production, so a relative path is enough. */
  apiBaseUrl: '/api',
};
