import { Component, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { Dictionary } from '../../core/i18n/dictionary';
import { I18nService } from '../../core/i18n/i18n.service';
import { ThemeToggle } from '../../shared/theme/theme-toggle';

/** The demo accounts, exactly as the realm ships them (DECISIONS.txt). */
export const DEMO_PASSWORD = 'demo1234';

/**
 * One demo account, as the landing page offers it.
 *
 * The username is the realm's and is never translated — it is what Keycloak matches and what the
 * visitor would otherwise type. Only the description comes from the dictionary.
 */
export interface DemoUser {
  readonly username: string;
  readonly describe: (t: Dictionary) => string;
}

export const DEMO_USERS: readonly DemoUser[] = [
  { username: 'operator', describe: (t) => t.demo.operator },
  { username: 'techniker', describe: (t) => t.demo.techniker },
  { username: 'schichtleiter', describe: (t) => t.demo.schichtleiter },
  { username: 'admin', describe: (t) => t.demo.admin },
];

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
  imports: [ThemeToggle],
  templateUrl: './landing.html',
  styleUrl: './landing.css',
})
export class Landing {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;
  protected readonly demoPassword = DEMO_PASSWORD;
  protected readonly demoUsers = DEMO_USERS;

  /** Set when this tab arrived here from a sign-out, so the landing page can confirm it happened. */
  protected readonly signedOut = signal(false);

  constructor() {
    // Read once, on arrival, and cleared as it is read: a "you have been signed out" that reappeared
    // on every later visit would stop meaning anything.
    this.signedOut.set(this.auth.consumeSignedOutNotice());

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
   *
   * With a username it is the same redirect carrying `login_hint`, so Keycloak prefills the field
   * and the visitor only types the password. It is emphatically not a one-click login: see
   * DECISIONS.txt on why the password grant and a credential-filling login theme were both rejected.
   */
  protected signIn(username?: string): void {
    this.auth.login(username);
  }
}
