import { TestBed } from '@angular/core/testing';
import { OAuthService } from 'angular-oauth2-oidc';
import { MockInstance, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from './auth.service';

/** Base64url-encodes a claims object into something shaped like a JWT payload segment. */
function fakeJwt(claims: Record<string, unknown>): string {
  const payload = btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_');
  return `header.${payload}.signature`;
}

/** Only the members AuthService actually calls; the rest of OAuthService is irrelevant here. */
type OAuthStub = Pick<
  OAuthService,
  | 'configure'
  | 'loadDiscoveryDocumentAndTryLogin'
  | 'hasValidAccessToken'
  | 'getAccessToken'
  | 'initCodeFlow'
  | 'logOut'
>;

describe('AuthService', () => {
  let oauth: { [K in keyof OAuthStub]: MockInstance };
  let service: AuthService;

  beforeEach(() => {
    oauth = {
      configure: vi.fn(),
      loadDiscoveryDocumentAndTryLogin: vi.fn().mockResolvedValue(true),
      hasValidAccessToken: vi.fn().mockReturnValue(false),
      getAccessToken: vi.fn().mockReturnValue(''),
      initCodeFlow: vi.fn(),
      logOut: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [AuthService, { provide: OAuthService, useValue: oauth }],
    });
    service = TestBed.inject(AuthService);
  });

  it('configures the client and completes a pending redirect on init', async () => {
    await service.init();

    expect(oauth.configure).toHaveBeenCalled();
    expect(oauth.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalled();
  });

  it('stays usable when the discovery document cannot be loaded', async () => {
    oauth.loadDiscoveryDocumentAndTryLogin.mockRejectedValue(new Error('Keycloak down'));

    await expect(service.init()).resolves.toBeUndefined();
    expect(service.isAuthenticated()).toBe(false);
  });

  it('reads username and realm roles from the access token', () => {
    oauth.hasValidAccessToken.mockReturnValue(true);
    oauth.getAccessToken.mockReturnValue(
      fakeJwt({
        preferred_username: 'techniker',
        realm_access: { roles: ['techniker', 'offline_access'] },
      }),
    );

    expect(service.isAuthenticated()).toBe(true);
    expect(service.username()).toBe('techniker');
    expect(service.realmRoles()).toEqual(['techniker', 'offline_access']);
  });

  it('reports no identity without a token', () => {
    expect(service.username()).toBe('');
    expect(service.realmRoles()).toEqual([]);
  });

  it('treats a malformed token as no identity instead of throwing', () => {
    oauth.getAccessToken.mockReturnValue('not-a-jwt');

    expect(() => service.username()).not.toThrow();
    expect(service.realmRoles()).toEqual([]);
  });

  it('delegates login and logout to the OIDC client', () => {
    service.login();
    service.logout();

    expect(oauth.initCodeFlow).toHaveBeenCalled();
    expect(oauth.logOut).toHaveBeenCalled();
  });
});
