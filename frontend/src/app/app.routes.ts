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
    // Write access is two roles' by decision: decision 3 of 2026-08-11 added the Techniker to the
    // Schichtleiter, and this guard caught up on 2026-08-28. The backend enforces it; this keeps an
    // operator who followed a bookmark out of a form that would 403 after they chose a file.
    canActivate: [roleGuard('techniker', 'schichtleiter')],
    title: 'Protokoll hochladen · Wartungsassistent',
  },
  {
    path: 'moderation',
    loadComponent: () => import('./features/moderation/moderation').then((m) => m.Moderation),
    // TWO ROLES, ONE ROUTE, AND THEY SEE DIFFERENT VIEWS OF IT.
    //
    // The admin reviews, approves and archives. The Schichtleiter corrects — and until 2026-08-14
    // could not reach this screen at all, which meant the correction endpoint they had held alone
    // since 2026-08-13 had no interface for anybody: the admin had the screen without the
    // permission, the corrector the permission without the screen.
    //
    // Opening the route does NOT hand the Schichtleiter the admin's view. The component renders the
    // controls each role may actually use (see `Moderation.canCorrect` / `canApprove` / `canArchive`)
    // and the backend refuses the rest regardless — hiding a control is presentation, and the
    // decision counts only server-side (NFR-3).
    canActivate: [roleGuard('admin', 'schichtleiter')],
    // Two roles, two jobs, and the browser tab should say which one is on screen. The heading in the
    // view is chosen the same way — see the component's `heading`.
    title: 'Protokolle · Wartungsassistent',
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
