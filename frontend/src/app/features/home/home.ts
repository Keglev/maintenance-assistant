import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

/** What GET /api/hello returns — the backend's view of the caller. */
interface HelloResponse {
  username: string;
  roles: string[];
}

/**
 * Protected landing page: shows who the token says you are.
 *
 * It also calls the backend's `/api/hello` through the dev proxy, which is what turns this from
 * "the browser can read a token" into proof that the backend accepted it too — the Phase 1
 * definition of done.
 */
@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home {
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);

  protected readonly username = this.auth.username;
  protected readonly realmRoles = this.auth.realmRoles;

  protected readonly backendIdentity = signal<HelloResponse | null>(null);
  protected readonly backendError = signal<string | null>(null);

  constructor() {
    this.http.get<HelloResponse>(`${environment.apiBaseUrl}/hello`).subscribe({
      next: (response) => this.backendIdentity.set(response),
      // The page stays useful when the backend is not running; it just says so.
      error: () => this.backendError.set('Backend not reachable at ' + environment.apiBaseUrl),
    });
  }

  protected signOut(): void {
    this.auth.logout();
  }
}
