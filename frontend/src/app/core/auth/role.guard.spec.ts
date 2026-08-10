import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { AuthService } from './auth.service';
import { homePath, roleGuard } from './role.guard';

/**
 * Which view a role is sent to when it asks for one it may not have.
 *
 * The admin case is a defect fix, not a preference: `/search` calls `GET /api/machines` first, that
 * endpoint refuses a role that may not ask questions, and an administrator's first screen after
 * login was therefore "Maschinenliste nicht verfügbar". Presentation, as always — the backend is
 * what refuses the calls (NFR-3) — but a landing page made of error messages is still a defect.
 */
describe('roleGuard', () => {
  let roles: string[];
  let authenticated: boolean;

  function runGuard(allowed: string[], url: string): boolean | UrlTree {
    return TestBed.runInInjectionContext(
      () =>
        roleGuard(...allowed)({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot) as
          | boolean
          | UrlTree,
    );
  }

  beforeEach(() => {
    roles = [];
    authenticated = true;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { isAuthenticated: () => authenticated, realmRoles: () => roles },
        },
      ],
    });
  });

  it('lets a role it names through', () => {
    roles = ['schichtleiter'];

    expect(runGuard(['schichtleiter'], '/upload')).toBe(true);
  });

  it('matches roles case-insensitively', () => {
    // A realm export that capitalises a role must not lock everybody out of a view.
    roles = ['Schichtleiter'];

    expect(runGuard(['schichtleiter'], '/upload')).toBe(true);
  });

  it('sends an anonymous caller to /login, whatever the route asked for', () => {
    authenticated = false;
    const router = TestBed.inject(Router);

    expect(runGuard(['admin'], '/moderation')).toEqual(router.createUrlTree(['/login']));
  });

  it('sends an admin who typed /search to the protocol management view', () => {
    roles = ['admin'];
    const router = TestBed.inject(Router);

    // The live defect: the admin's landing page was a search form whose machine list 403s.
    expect(runGuard(['operator', 'techniker', 'schichtleiter'], '/search')).toEqual(
      router.createUrlTree(['/moderation']),
    );
  });

  it('sends a shop-floor role turned away from moderation to search', () => {
    roles = ['operator'];
    const router = TestBed.inject(Router);

    expect(runGuard(['admin'], '/moderation')).toEqual(router.createUrlTree(['/search']));
  });

  it('does not bounce a refused route back to itself', () => {
    // A caller holding no role this application knows would otherwise be redirected to the very
    // route that just refused them, and the router would follow that until it gave up.
    roles = ['offline_access'];
    const router = TestBed.inject(Router);

    expect(runGuard(['operator', 'techniker', 'schichtleiter'], '/search')).toEqual(
      router.createUrlTree(['/']),
    );
  });

  it('names moderation as the admin home and search for everyone else', () => {
    // `/home` is the OIDC redirect URI, so this is literally where a completed login lands.
    expect(homePath(['admin'])).toBe('/moderation');
    expect(homePath(['schichtleiter'])).toBe('/search');
    expect(homePath([])).toBe('/search');
  });
});
