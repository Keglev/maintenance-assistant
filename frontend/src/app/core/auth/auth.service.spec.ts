import { TestBed } from '@angular/core/testing';
import { OAuthService } from 'angular-oauth2-oidc';

import { AuthService } from './auth.service';

/** Base64url-encodes a claims object into something shaped like a JWT payload segment. */
function fakeJwt(claims: Record<string, unknown>): string {
  const payload = btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_');
  return `header.${payload}.signature`;
}

describe('AuthService', () => {
  let oauth: jasmine.SpyObj<OAuthService>;
  let service: AuthService;

  beforeEach(() => {
    oauth = jasmine.createSpyObj<OAuthService>('OAuthService', [
      'configure',
      'loadDiscoveryDocumentAndTryLogin',
      'hasValidAccessToken',
      'getAccessToken',
      'initCodeFlow',
      'logOut',
    ]);
    oauth.loadDiscoveryDocumentAndTryLogin.and.resolveTo(true);

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
    oauth.loadDiscoveryDocumentAndTryLogin.and.rejectWith(new Error('Keycloak down'));
    oauth.hasValidAccessToken.and.returnValue(false);

    await expectAsync(service.init()).toBeResolved();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('reads username and realm roles from the access token', () => {
    oauth.hasValidAccessToken.and.returnValue(true);
    oauth.getAccessToken.and.returnValue(
      fakeJwt({
        preferred_username: 'techniker',
        realm_access: { roles: ['techniker', 'offline_access'] },
      }),
    );

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.username()).toBe('techniker');
    expect(service.realmRoles()).toEqual(['techniker', 'offline_access']);
  });

  it('reports no identity without a token', () => {
    oauth.hasValidAccessToken.and.returnValue(false);
    oauth.getAccessToken.and.returnValue('');

    expect(service.username()).toBe('');
    expect(service.realmRoles()).toEqual([]);
  });

  it('treats a malformed token as no identity instead of throwing', () => {
    oauth.getAccessToken.and.returnValue('not-a-jwt');

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
