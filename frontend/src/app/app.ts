import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { I18nService } from './core/i18n/i18n.service';
import { UiLanguage } from './core/i18n/dictionary';
import { Dialog } from './shared/dialog/dialog';
import { HelpDialog } from './shared/help/help-dialog';
import { ThemeToggle } from './shared/theme/theme-toggle';

/** Where the footer points. Fixed URLs, so they are not worth a runtime configuration lookup. */
export const DOCS_URL = 'https://keglev.github.io/maintenance-assistant/';
export const REPO_URL = 'https://github.com/Keglev/maintenance-assistant';
export const AI_USAGE_URL = 'https://github.com/Keglev/maintenance-assistant/blob/main/AI-USAGE.md';

/** The realm roles this interface knows how to name, most privileged first. */
const KNOWN_ROLES = ['admin', 'schichtleiter', 'techniker', 'operator'] as const;

type KnownRole = (typeof KNOWN_ROLES)[number];

/** Application shell: header, navigation, the language switch, the footer and the routed view. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Dialog, HelpDialog, ThemeToggle],
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

  protected readonly docsUrl = DOCS_URL;
  protected readonly repoUrl = REPO_URL;
  protected readonly aiUsageUrl = AI_USAGE_URL;

  protected readonly helpOpen = signal(false);
  protected readonly signOutOpen = signal(false);

  /**
   * The role to show next to the name.
   *
   * One badge, not a list: a demo user holds one meaningful role, and a row of chips would suggest
   * a permission model the application does not have. The highest-privilege match wins so that a
   * Schichtleiter who also carries `operator` is not labelled the lesser of the two.
   */
  protected readonly roleLabel = computed(() => {
    const held = new Set(this.auth.realmRoles().map((role) => role.toLowerCase()));
    const role = KNOWN_ROLES.find((candidate) => held.has(candidate));
    return role ? this.t().roles[role as KnownRole] : '';
  });

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

  /**
   * Signs out, once the user has said so twice.
   *
   * The confirmation exists for the touchscreen. "Abmelden" sits in a header toolbar next to the
   * language switch on a device operated with work gloves, and an accidental sign-out costs a full
   * Keycloak round trip in the middle of a fault — the one moment nobody has time for it.
   */
  protected confirmSignOut(): void {
    this.signOutOpen.set(false);
    this.auth.logout();
  }
}
