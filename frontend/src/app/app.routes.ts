import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

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
    canActivate: [authGuard],
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
  // `/home` is the OIDC redirect URI registered in the Keycloak realm, so the path has to keep
  // resolving — but the Phase 1 identity page it used to show is gone. Someone arriving from a
  // completed login wants the thing the application is for.
  { path: 'home', redirectTo: 'search' },
  { path: '**', redirectTo: '' },
];
