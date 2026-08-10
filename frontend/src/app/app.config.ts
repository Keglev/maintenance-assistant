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
import { FontScaleService } from './core/theme/font-scale.service';
import { ThemeService } from './core/theme/theme.service';
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
    // Instantiated at startup so the stored theme is applied and the OS listener is attached before
    // the first view renders. `public/theme-init.js` has already set the attribute before the first
    // paint; this takes ownership of it — without the eager injection nothing would construct the
    // service until a view happened to use the toggle.
    provideAppInitializer(() => void inject(ThemeService)),
    // Same reason as the theme: theme-init.js has already set data-font before the first paint, and
    // this takes ownership of it so the settings dialog can change it.
    provideAppInitializer(() => void inject(FontScaleService)),
    // Discovery document and redirect handling must complete before the first route renders,
    // otherwise the guard would see "not authenticated" while a login is still in flight.
    provideAppInitializer(() => inject(AuthService).init()),
  ],
};
