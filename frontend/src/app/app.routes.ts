import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { homePath, roleGuard } from './core/auth/role.guard';

/** The three roles that work on the shop floor. Admin is deliberately not one of them. */
const SHOP_FLOOR = ['operator', 'techniker', 'schichtleiter'];

export const routes: Routes = [
  // The landing page is what a logged-out visitor gets, at `/` and at `/login` alike: the guard
  // sends unauthenticated callers to `/login` and `postLogoutRedirectUri` returns them there after
  // signing out, and both of those people are better served by the pitch than by a bare button.
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./features/landing/landing').then((m) => m.Landing),
    title: 'Wartungsassistent · Maintenance Assistant',
  },
  {
    path: 'login',
    loadComponent: () => import('./features/landing/landing').then((m) => m.Landing),
    title: 'Anmelden · Wartungsassistent',
  },
  {
    path: 'search',
    loadComponent: () => import('./features/search/search').then((m) => m.Search),
    // Shop floor only, and the admin's exclusion is the point: they may not ask questions by
    // decision, so this view could only ever show them a picker they cannot fill and a form that
    // 403s. Typing the URL sends them to moderation rather than to a broken page.
    canActivate: [roleGuard(...SHOP_FLOOR)],
    title: 'Suche · Wartungsassistent',
  },
  {
    path: 'upload',
    loadComponent: () => import('./features/upload/upload').then((m) => m.Upload),
    // Write access is one role's by decision (DECISIONS.txt). The backend enforces it; this keeps
    // an operator who followed a bookmark out of a form that would 403 after they chose a file.
    canActivate: [roleGuard('schichtleiter')],
    title: 'Protokoll hochladen · Wartungsassistent',
  },
  {
    path: 'moderation',
    loadComponent: () => import('./features/moderation/moderation').then((m) => m.Moderation),
    // The admin's first shop-floor route. Guarded here and, decisively, on every endpoint it calls:
    // hiding a route is presentation, and the backend refuses the calls regardless (NFR-3).
    canActivate: [roleGuard('admin')],
    title: 'Protokollverwaltung · Wartungsassistent',
  },
  // `/home` is the OIDC redirect URI registered in the Keycloak realm, so the path has to keep
  // resolving — but the Phase 1 identity page it used to show is gone. Someone arriving from a
  // completed login wants the thing the application is for, and which thing that is depends on
  // their role: a fixed `redirectTo: 'search'` is what landed an admin on a view full of errors.
  {
    path: 'home',
    redirectTo: () => inject(Router).parseUrl(homePath(inject(AuthService).realmRoles())),
  },
  { path: '**', redirectTo: '' },
];
