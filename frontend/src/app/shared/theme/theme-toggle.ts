import { Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';
import { ThemeChoice, ThemeService } from '../../core/theme/theme.service';

/**
 * The theme control: light and dark, with the operating system as the silent default.
 *
 * There is no "system" button. Until the user presses one of these two, the application follows
 * `prefers-color-scheme` and says nothing about it — a first visit already does the right thing, and
 * a third segment explaining that costs a third of the control to state the default. Pressing either
 * button stores an explicit choice, which then wins over the operating system.
 *
 * **The pressed state follows what is PAINTED, not what was chosen.** With no stored choice neither
 * button would be pressed if it tracked the choice, and the control would look broken on a first
 * visit. Tracking the resolved theme instead means it always answers the question the user is
 * actually asking it: which one am I looking at.
 */
@Component({
  selector: 'app-theme-toggle',
  templateUrl: './theme-toggle.html',
  styleUrl: './theme-toggle.css',
})
export class ThemeToggle {
  private readonly i18n = inject(I18nService);
  private readonly theme = inject(ThemeService);

  protected readonly t = this.i18n.t;
  protected readonly resolved = this.theme.resolved;

  protected use(choice: ThemeChoice): void {
    this.theme.use(choice);
  }
}
