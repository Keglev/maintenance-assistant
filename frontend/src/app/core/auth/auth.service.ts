import { Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';

import { ConfigService } from '../config/config.service';
import { buildAuthConfig } from './auth.config';

/** Shape of the Keycloak access token claims this application reads. */
interface AccessTokenClaims {
  preferred_username?: string;
  realm_access?: { roles?: string[] };
}

/**
 * Wraps `angular-oauth2-oidc` so components never touch the OIDC library directly.
 *
 * Roles are read from the **access token**, not the ID token: Keycloak puts realm roles in
 * `realm_access.roles` there. They drive what the UI shows — the backend enforces the same roles
 * server-side, because hiding a button is not a security control (NFR-3).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oauth = inject(OAuthService);
  private readonly configService = inject(ConfigService);

  /** Bumped after every login/logout so the derived signals recompute. */
  private readonly authState = signal(0);

  readonly isAuthenticated = computed(() => {
    this.authState();
    return this.oauth.hasValidAccessToken();
  });

  readonly username = computed(() => {
    this.authState();
    return this.claims()?.preferred_username ?? '';
  });

  readonly realmRoles = computed(() => {
    this.authState();
    return this.claims()?.realm_access?.roles ?? [];
  });

  /**
   * Configures the client, fetches the discovery document and completes a redirect that is
   * coming back from Keycloak. Runs once during application startup.
   */
  async init(): Promise<void> {
    // The deployment's config.json decides which Keycloak this talks to, so it
    // has to be read before the client is configured.
    await this.configService.load();
    this.oauth.configure(buildAuthConfig(this.configService.config));
    try {
      await this.oauth.loadDiscoveryDocumentAndTryLogin();
    } catch {
      // Keycloak being unreachable must not block the application from rendering; the user
      // simply stays unauthenticated and /login reports it.
    }
    this.authState.update((value) => value + 1);
  }

  /** Sends the browser to Keycloak's login page. */
  login(): void {
    this.oauth.initCodeFlow();
  }

  logout(): void {
    this.oauth.logOut();
    this.authState.update((value) => value + 1);
  }

  /** Decodes the access token payload; returns null when there is no valid token. */
  private claims(): AccessTokenClaims | null {
    const token = this.oauth.getAccessToken();
    if (!token) {
      return null;
    }

    const payload = token.split('.')[1];
    if (!payload) {
      return null;
    }

    try {
      const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      return JSON.parse(json) as AccessTokenClaims;
    } catch {
      // A malformed token is treated as no token rather than crashing the view.
      return null;
    }
  }
}
