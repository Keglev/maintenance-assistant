import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { I18nService } from './core/i18n/i18n.service';
import { UiLanguage } from './core/i18n/dictionary';
import { Dialog } from './shared/dialog/dialog';
import { HealthDot } from './shared/health/health-dot';
import { HelpDialog } from './shared/help/help-dialog';
import { SettingsDialog } from './shared/settings/settings-dialog';
import { ThemeToggle } from './shared/theme/theme-toggle';

/** Where the footer points. Fixed URLs, so they are not worth a runtime configuration lookup. */
const DOCS_URL = 'https://keglev.github.io/maintenance-assistant/';
const REPO_URL = 'https://github.com/Keglev/maintenance-assistant';
const AI_USAGE_URL = 'https://github.com/Keglev/maintenance-assistant/blob/main/AI-USAGE.md';

/** The realm roles this interface knows how to name, most privileged first. */
const KNOWN_ROLES = ['admin', 'schichtleiter', 'techniker', 'operator'] as const;

type KnownRole = (typeof KNOWN_ROLES)[number];

/** Application shell: header, navigation, the language switch, the footer and the routed view. */
@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    Dialog,
    HealthDot,
    HelpDialog,
    SettingsDialog,
    ThemeToggle,
  ],
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
  protected readonly settingsOpen = signal(false);

  /** The one role whose navigation differs in kind rather than in degree. */
  private readonly isAdmin = computed(() =>
    this.auth.realmRoles().some((role) => role.toLowerCase() === 'admin'),
  );

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
   * Whether to offer the search view.
   *
   * Everyone but the admin, and that is the same defect the routing fixes seen from the header: an
   * admin may not ask questions by decision, so the entry led to a view whose machine picker they
   * are not allowed to fill. A nav entry onto an error message is worse than no nav entry.
   */
  protected readonly canSearch = computed(() => !this.isAdmin());

  /**
   * Whether to offer the upload view at all.
   *
   * The right to write was ONE role's by decision until 2026-08-11 and has been TWO roles' since:
   * decision 3 gave the Techniker the upload, because the person standing at the machine when it
   * is fixed is the person who knows what happened to it. The backend applied it in v1.2.0 and
   * this computed did not, so until 2026-08-28 a Techniker had the permission and no door onto it.
   * Correcting did not move and is still the Schichtleiter's.
   *
   * Hiding the link is presentation, not protection — the route guard and, above all, the backend
   * decide who may upload. What it buys is that the two roles who cannot upload are not shown a
   * door that opens onto a 403.
   */
  protected readonly canUpload = computed(() =>
    this.auth
      .realmRoles()
      .some((role) => ['techniker', 'schichtleiter'].includes(role.toLowerCase())),
  );

  /**
   * Whether to offer the protocol view — and it is two different views behind one entry.
   *
   * <p>The admin reviews, approves and archives. The Schichtleiter corrects, and since 2026-08-14
   * reaches the same route to do it: they had held the correction endpoint alone since 2026-08-13
   * with no door onto it, so the permission existed and the path did not.
   *
   * <p>Presentation, like {@link canUpload} — the route guard and, decisively, the backend decide.
   * What it buys is that the two roles with a job here have a door and the two without one do not.
   * What the door OPENS ONTO differs by role: the component renders the controls each may use, and
   * {@link nav.moderation} is worded for the reader rather than for the route.
   */
  protected readonly canModerate = computed(
    () =>
      this.isAdmin() ||
      this.auth.realmRoles().some((role) => role.toLowerCase() === 'schichtleiter'),
  );

  /**
   * What to call that entry, because the two roles do not do the same thing behind it.
   *
   * "Protokollverwaltung" describes reviewing and removing; a Schichtleiter does neither. Naming the
   * link after the job is part of what makes the trust chain legible from the screen — a corrector
   * who clicks "Verwaltung" and finds no delete button has been told the wrong thing about their
   * own role.
   */
  protected readonly moderationLabel = computed(() =>
    this.isAdmin() ? this.t().nav.moderation : this.t().nav.corrections,
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
