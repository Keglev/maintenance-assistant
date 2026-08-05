import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { AuthService } from './core/auth/auth.service';
import { environment } from '../environments/environment';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptorsFromDi()),
    provideOAuthClient({
      // The access token is attached only to our own API, never to third-party hosts.
      resourceServer: {
        allowedUrls: [environment.apiBaseUrl],
        sendAccessToken: true,
      },
    }),
    // Discovery document and redirect handling must complete before the first route renders,
    // otherwise the guard would see "not authenticated" while a login is still in flight.
    provideAppInitializer(() => inject(AuthService).init()),
  ],
};
