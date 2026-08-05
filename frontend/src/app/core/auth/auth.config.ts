import { AuthConfig } from 'angular-oauth2-oidc';

import { environment } from '../../../environments/environment';

/**
 * OIDC client configuration for the Keycloak realm `maintenance` (ADR-003).
 *
 * Authorization Code Flow with PKCE: the browser is a public client that cannot keep a secret,
 * so PKCE replaces the client secret. `responseType: 'code'` plus `disablePKCE: false` is what
 * makes this the code flow rather than the deprecated implicit flow.
 */
export const authConfig: AuthConfig = {
  issuer: environment.keycloakIssuer,
  clientId: environment.keycloakClientId,
  responseType: 'code',
  redirectUri: window.location.origin + '/home',
  postLogoutRedirectUri: window.location.origin + '/login',

  // `openid profile` yields preferred_username; realm roles ride in the access token.
  scope: 'openid profile email',

  // Development talks plain HTTP to localhost:8081. Production runs behind TLS, where this
  // guard is back on and the library refuses a non-HTTPS issuer.
  requireHttps: environment.production,

  showDebugInformation: !environment.production,
  useSilentRefresh: false,
};
