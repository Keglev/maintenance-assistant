import { Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';
import { ThemeChoice, ThemeService } from '../../core/theme/theme.service';

/**
 * The three-state theme control: system, light, dark.
 *
 * Three buttons rather than a two-state switch, because "follow the operating system" is a real
 * choice and not the absence of one — a two-state control silently turns every user into a manual
 * one the first time they touch it, and then a tablet that used to go dark at dusk stops doing it.
 *
 * Modelled on the language switch next to it: a labelled group of buttons with `aria-pressed`,
 * rather than a menu. With three options a menu costs a click to discover what three icons already
 * show, and on a touchscreen a popover is the harder target.
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
  protected readonly choice = this.theme.choice;

  protected use(choice: ThemeChoice): void {
    this.theme.use(choice);
  }
}
