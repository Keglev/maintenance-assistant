/**
 * Production defaults, compiled into the bundle.
 *
 * These are only the fallback. The deployment supplies `/config.json`, which
 * ConfigService merges over them at startup, so the same image can be pointed at
 * a different Keycloak without a rebuild. They are kept accurate anyway: an
 * image that loses its bind mount should still reach the real deployment rather
 * than a placeholder host.
 */
export const environment = {
  production: true,

  /** Keycloak realm base URL — the OIDC issuer (ADR-003). */
  keycloakIssuer: 'https://auth.smartsupply.com.de/realms/maintenance',

  /** Public client from the versioned realm export; Auth Code Flow + PKCE, holds no secret. */
  keycloakClientId: 'frontend',

  /** Backend base URL. Same-origin behind the reverse proxy, so a relative path. */
  apiBaseUrl: '/api',
};
