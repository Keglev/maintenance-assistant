import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { I18nService } from './core/i18n/i18n.service';
import { UiLanguage } from './core/i18n/dictionary';

/** Application shell: navigation, the language switch, and the routed view. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;
  protected readonly language = this.i18n.language;
  protected readonly isAuthenticated = this.auth.isAuthenticated;
  protected readonly username = this.auth.username;

  /**
   * Whether to offer the upload view at all.
   *
   * Hiding the link is presentation, not protection — the route guard and, above all, the backend
   * decide who may upload. What it buys is that the three roles who cannot upload are not shown a
   * door that opens onto a 403.
   */
  protected readonly canUpload = computed(() =>
    this.auth.realmRoles().some((role) => role.toLowerCase() === 'schichtleiter'),
  );

  protected use(language: UiLanguage): void {
    this.i18n.use(language);
  }

  protected signOut(): void {
    this.auth.logout();
  }
}
