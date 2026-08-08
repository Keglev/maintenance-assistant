import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Keeps a route out of the hands of roles that have no business on it.
 *
 * Like {@link authGuard}, this is convenience and not security: the upload endpoint refuses anyone
 * but a Schichtleiter server-side, which is the only place the decision counts (NFR-3). What this
 * buys is that an operator following a bookmarked `/upload` link gets the search view rather than a
 * form that produces a 403 after they have chosen a file.
 */
export function roleGuard(...allowed: string[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (!auth.isAuthenticated()) {
      return router.createUrlTree(['/login']);
    }
    // Keycloak realm roles are lower-case in the token; the comparison is normalised so a realm
    // export that capitalises one does not silently lock everybody out of a view.
    const roles = auth.realmRoles().map((role) => role.toLowerCase());
    return allowed.some((role) => roles.includes(role.toLowerCase()))
      ? true
      : router.createUrlTree(['/search']);
  };
}
