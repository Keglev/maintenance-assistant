import { Component, computed, inject, input, output } from '@angular/core';

import { version } from '../../../../package.json';
import { AuthService } from '../../core/auth/auth.service';
import { FontScale, FontScaleService } from '../../core/theme/font-scale.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Dialog } from '../dialog/dialog';

/** Where the settings dialog points. Same fixed URLs the footer uses. */
export const DOCS_URL = 'https://keglev.github.io/maintenance-assistant/';
export const REPO_URL = 'https://github.com/Keglev/maintenance-assistant';

/** The realm roles this interface knows how to name, most privileged first. */
const KNOWN_ROLES = ['admin', 'schichtleiter', 'techniker', 'operator'] as const;

/**
 * The "System" dialog behind the gear: what this application is, who is signed in, and how large it
 * should be drawn.
 *
 * It is where the font control lives rather than the header, because it is set once and then left
 * alone — a permanent third control in a toolbar for a decision made on the first day would cost
 * room on every screen after it. The gear sits with the user block, away from the language switch,
 * which is where a B2B interface puts settings.
 *
 * The version comes from `package.json` at build time. A hand-maintained constant is a string that
 * is wrong from the first release nobody remembered to edit it for.
 */
@Component({
  selector: 'app-settings-dialog',
  imports: [Dialog],
  templateUrl: './settings-dialog.html',
  styleUrl: './settings-dialog.css',
})
export class SettingsDialog {
  private readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly fonts = inject(FontScaleService);

  protected readonly t = this.i18n.t;

  readonly open = input(false);
  readonly closed = output<void>();

  protected readonly version = version;
  protected readonly docsUrl = DOCS_URL;
  protected readonly repoUrl = REPO_URL;

  protected readonly isAuthenticated = this.auth.isAuthenticated;
  protected readonly username = this.auth.username;
  protected readonly scale = this.fonts.scale;

  /** The three steps, in the order they are offered. */
  protected readonly scales: readonly FontScale[] = ['normal', 'lg', 'xl'];

  protected readonly roleLabel = computed(() => {
    const held = new Set(this.auth.realmRoles().map((role) => role.toLowerCase()));
    const role = KNOWN_ROLES.find((candidate) => held.has(candidate));
    return role ? this.t().roles[role] : '';
  });

  /**
   * "Signed in since 21:14" — or nothing at all.
   *
   * The row disappears rather than showing a guess when the token carries no `iat`: a fabricated
   * time on a settings screen is worse than an absent row, because it looks like a fact.
   */
  protected readonly signedInAt = computed(() => {
    const at = this.auth.signedInAt();
    if (!at) {
      return '';
    }
    return at.toLocaleTimeString(this.i18n.language(), { hour: '2-digit', minute: '2-digit' });
  });

  protected scaleLabel(scale: FontScale): string {
    const labels = this.t().settings;
    switch (scale) {
      case 'lg':
        return labels.fontLarge;
      case 'xl':
        return labels.fontExtraLarge;
      default:
        return labels.fontNormal;
    }
  }

  protected useScale(scale: FontScale): void {
    this.fonts.use(scale);
  }
}
