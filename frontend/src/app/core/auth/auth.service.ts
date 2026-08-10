import { Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';

import { ConfigService } from '../config/config.service';
import { buildAuthConfig } from './auth.config';

/**
 * Marks that this tab signed out deliberately, so the landing page can say so.
 *
 * sessionStorage rather than a query parameter on the redirect: `postLogoutRedirectUri` is matched
 * against the URIs registered in the realm, and decorating it would risk a Keycloak change this
 * project has decided not to need. It is also the right lifetime — the notice belongs to the tab
 * that signed out, not to every tab on the machine. `logOut()` clears only its own keys, so this
 * one survives the round trip through Keycloak.
 */
const SIGNED_OUT_KEY = 'maintenance-assistant.signed-out';

/** Shape of the Keycloak access token claims this application reads. */
interface AccessTokenClaims {
  preferred_username?: string;
  realm_access?: { roles?: string[] };
  /** Issued-at, seconds since the epoch. Standard JWT claim; Keycloak always sends it. */
  iat?: number;
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
   * When this session's token was issued, or null if the claim is missing or nonsensical.
   *
   * Null rather than a guess: "signed in since" is a fact or it is nothing, and a fabricated time
   * on a settings screen is worse than an absent row. The token's `iat` is the honest answer to the
   * question the user is asking — the session ends 15 minutes after it (ADR-005).
   */
  readonly signedInAt = computed<Date | null>(() => {
    this.authState();
    const issuedAt = this.claims()?.iat;
    if (typeof issuedAt !== 'number' || !Number.isFinite(issuedAt)) {
      return null;
    }
    const at = new Date(issuedAt * 1000);
    return Number.isNaN(at.getTime()) ? null : at;
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

  /**
   * Sends the browser to Keycloak's login page.
   *
   * `loginHint` is passed as the standard `login_hint` authorization request parameter, which
   * Keycloak uses to prefill the username field. It is a hint on the normal Auth Code + PKCE
   * request and nothing more: the visitor still authenticates at Keycloak, and the password never
   * passes through this application. The demo buttons on the landing page are its only caller.
   */
  login(loginHint?: string): void {
    this.oauth.initCodeFlow('', loginHint ? { login_hint: loginHint } : {});
  }

  logout(): void {
    this.rememberSignOut();
    this.oauth.logOut();
    this.authState.update((value) => value + 1);
  }

  /**
   * Whether this tab has just signed out, clearing the flag as it reports it.
   *
   * Read-and-clear rather than read: the notice is about the sign-out that just happened, and one
   * that reappeared on every later visit to the landing page would stop meaning anything.
   */
  consumeSignedOutNotice(): boolean {
    try {
      const signedOut = sessionStorage.getItem(SIGNED_OUT_KEY) !== null;
      sessionStorage.removeItem(SIGNED_OUT_KEY);
      return signedOut;
    } catch {
      // Storage the browser refuses to hand over costs a confirmation message, nothing more.
      return false;
    }
  }

  private rememberSignOut(): void {
    try {
      sessionStorage.setItem(SIGNED_OUT_KEY, '1');
    } catch {
      // See above: the sign-out itself must not depend on storage being writable.
    }
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
