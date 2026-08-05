/**
 * Local development environment, matching the compose stack in docker/.
 *
 * Keycloak is reached directly on port 8081, while /api goes through the dev-server proxy
 * (proxy.conf.json) so the browser sees a single origin and the backend needs no CORS setup.
 */
export const environment = {
  production: false,
  keycloakIssuer: 'http://localhost:8081/realms/maintenance',
  keycloakClientId: 'frontend',
  apiBaseUrl: '/api',
};
