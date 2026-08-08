import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
    title: 'Sign in · maintenance-assistant',
  },
  {
    path: 'search',
    loadComponent: () => import('./features/search/search').then((m) => m.Search),
    canActivate: [authGuard],
    title: 'Search · maintenance-assistant',
  },
  {
    path: 'upload',
    loadComponent: () => import('./features/upload/upload').then((m) => m.Upload),
    // Write access is one role's by decision (DECISIONS.txt). The backend enforces it; this keeps
    // an operator who followed a bookmark out of a form that would 403 after they chose a file.
    canActivate: [roleGuard('schichtleiter')],
    title: 'Upload · maintenance-assistant',
  },
  {
    path: 'home',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
    canActivate: [authGuard],
    title: 'Home · maintenance-assistant',
  },
  // Search is the landing page now rather than the Phase 1 identity page: it is what the
  // application is for, and a demo visitor should arrive at the thing worth seeing.
  { path: '', pathMatch: 'full', redirectTo: 'search' },
  { path: '**', redirectTo: 'search' },
];
