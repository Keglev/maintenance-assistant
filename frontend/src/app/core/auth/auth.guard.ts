import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Blocks routes for callers without a valid access token and sends them to /login.
 *
 * This is convenience, not security: every protected resource is enforced again by the backend
 * resource server, which is the only place an authorization decision actually counts (NFR-3).
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.isAuthenticated() ? true : router.createUrlTree(['/login']);
};
