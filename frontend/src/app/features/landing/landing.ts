import { Component, effect, inject } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';

/** The demo accounts, exactly as the realm ships them (DECISIONS.txt). */
export const DEMO_PASSWORD = 'demo1234';

/**
 * The logged-out landing page: what this is, why it exists, and how to try it.
 *
 * It is the first screen a recruiter sees and it has about ninety seconds. So it leads with the
 * problem rather than the stack, states the two things that make the answers trustworthy (sourced or
 * labelled, processed in the EU), and puts the demo accounts and three questions that actually
 * retrieve something within reach — a demo whose visitor cannot think of a question shows nothing.
 *
 * It replaces the Phase 1 login page and keeps its route, because `postLogoutRedirectUri` points at
 * `/login`: signing out lands here, which is the right place to land.
 */
@Component({
  selector: 'app-landing',
  templateUrl: './landing.html',
  styleUrl: './landing.css',
})
export class Landing {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;
  protected readonly demoPassword = DEMO_PASSWORD;

  constructor() {
    // Someone who is already signed in has no business on the pitch; they came to ask a question.
    effect(() => {
      if (this.auth.isAuthenticated()) {
        void this.router.navigate(['/search']);
      }
    });
  }

  /**
   * Starts the redirect to Keycloak on an explicit click rather than on load: an automatic redirect
   * turns any transient Keycloak error into a loop the visitor cannot escape.
   */
  protected signIn(): void {
    this.auth.login();
  }
}
