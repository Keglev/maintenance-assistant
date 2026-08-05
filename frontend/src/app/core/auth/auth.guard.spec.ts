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
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  let authenticated: boolean;

  function runGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(
      () => authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as boolean | UrlTree,
    );
  }

  beforeEach(() => {
    authenticated = false;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: () => authenticated } },
      ],
    });
  });

  it('allows an authenticated caller through', () => {
    authenticated = true;

    expect(runGuard()).toBe(true);
  });

  it('redirects an anonymous caller to /login', () => {
    const router = TestBed.inject(Router);

    const result = runGuard();

    expect(result).toEqual(router.createUrlTree(['/login']));
  });
});
