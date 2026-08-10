import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

/**
 * Where a role belongs when it has nowhere else to be: after login, and after being turned away
 * from a route it may not have.
 *
 * The admin's home is moderation, and that is a defect fix rather than a preference. Their home was
 * `/search`, the search view's first call is `GET /api/machines`, and until this PR that endpoint
 * refused a role with no shop-floor function — so an administrator's first screen was an error
 * message. Search is not a view an admin can use at all: they may not ask questions, by decision.
 */
export function homePath(realmRoles: readonly string[]): string {
  return realmRoles.some((role) => role.toLowerCase() === 'admin') ? '/moderation' : '/search';
}

/**
 * Keeps a route out of the hands of roles that have no business on it.
 *
 * Like {@link authGuard}, this is convenience and not security: the upload endpoint refuses anyone
 * but a Schichtleiter server-side, which is the only place the decision counts (NFR-3). What this
 * buys is that an operator following a bookmarked `/upload` link gets their own view rather than a
 * form that produces a 403 after they have chosen a file — and that an admin who types `/search`
 * gets the view they can use rather than a machine picker that cannot be filled.
 *
 * Turned-away callers land on {@link homePath} rather than on a hard-coded `/search`, which is what
 * makes one guard serve both directions: `/upload` is refused to an admin and `/search` is refused
 * to an admin, and both send them to moderation.
 */
export function roleGuard(...allowed: string[]): CanActivateFn {
  return (route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    // Delegated rather than repeated: "no token means /login" is one rule, and a second copy of it
    // here is a second place for it to drift.
    const authenticated = authGuard(route, state);
    if (authenticated !== true) {
      return authenticated;
    }
    // Keycloak realm roles are lower-case in the token; the comparison is normalised so a realm
    // export that capitalises one does not silently lock everybody out of a view.
    const roles = auth.realmRoles().map((role) => role.toLowerCase());
    if (allowed.some((role) => roles.includes(role.toLowerCase()))) {
      return true;
    }
    const home = homePath(roles);
    // A caller holding no role this application knows would otherwise be redirected to the very
    // route that just refused them, which the router follows until it gives up. Sending them to the
    // landing page instead costs one line and cannot loop.
    return router.createUrlTree([home === state.url.split('?')[0] ? '/' : home]);
  };
}
