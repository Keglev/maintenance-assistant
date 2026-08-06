import { AuthConfig } from 'angular-oauth2-oidc';

import { RuntimeConfig } from '../config/runtime-config';

/**
 * Builds the OIDC client configuration for the Keycloak realm `maintenance`
 * (ADR-003).
 *
 * A function rather than a constant because the issuer is only known once
 * `/config.json` has been read — the image is environment-agnostic, so nothing
 * about the deployment can be decided at module-evaluation time.
 *
 * Authorization Code Flow with PKCE: the browser is a public client that cannot
 * keep a secret, so PKCE replaces the client secret.
 */
export function buildAuthConfig(config: RuntimeConfig): AuthConfig {
  const isHttps = window.location.protocol === 'https:';

  return {
    issuer: config.keycloakIssuer,
    clientId: config.keycloakClientId,
    responseType: 'code',
    redirectUri: window.location.origin + '/home',
    postLogoutRedirectUri: window.location.origin + '/login',

    // `openid profile` yields preferred_username; realm roles ride in the access token.
    scope: 'openid profile email',

    // Derived from how the page was actually served, not from a build flag:
    // production is behind TLS and must keep the check, while a developer on
    // http://localhost talks to Keycloak over plain HTTP.
    requireHttps: isHttps,

    showDebugInformation: !isHttps,
    useSilentRefresh: false,
  };
}
