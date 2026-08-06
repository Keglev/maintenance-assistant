/**
 * Configuration the application reads at runtime rather than at build time.
 *
 * The container image is built once by CI and deployed to every environment, so
 * it cannot contain a hostname. The deployment supplies `/config.json` instead —
 * a bind mount in production, a static file under `public/` in development — and
 * the values compiled into `environment.ts` are the fallback when that file is
 * missing or unreadable.
 */
export interface RuntimeConfig {
  /** Keycloak realm base URL — the OIDC issuer. */
  keycloakIssuer: string;

  /** Public client id from the versioned realm export. */
  keycloakClientId: string;

  /** Backend base URL. Same-origin in every environment, so a relative path. */
  apiBaseUrl: string;
}
