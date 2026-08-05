import { Component, effect, inject } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';

/**
 * Login view: the trigger for the redirect to Keycloak.
 *
 * The redirect is started by an explicit click rather than automatically on load. An automatic
 * redirect turns any transient Keycloak error into a redirect loop the user cannot escape.
 */
@Component({
  selector: 'app-login',
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  constructor() {
    // Someone who is already signed in has no business on the login page.
    effect(() => {
      if (this.auth.isAuthenticated()) {
        void this.router.navigate(['/home']);
      }
    });
  }

  signIn(): void {
    this.auth.login();
  }
}
