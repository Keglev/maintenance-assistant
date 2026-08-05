import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
    title: 'Sign in · maintenance-assistant',
  },
  {
    path: 'home',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
    canActivate: [authGuard],
    title: 'Home · maintenance-assistant',
  },
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: '**', redirectTo: 'home' },
];
