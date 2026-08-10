import { DOCUMENT } from '@angular/common';
import { Injectable, computed, effect, inject, signal } from '@angular/core';

/**
 * What the user chose, which is not the same as what is painted.
 *
 * `system` is the absence of a choice rather than a third button. The control offers light and dark;
 * until one of them is pressed the application silently follows the operating system, which is what
 * a first visit should do without spending a third of a segmented control saying so.
 */
export type ThemeChoice = 'light' | 'dark' | 'system';

/** What is actually on screen once `system` has been resolved against the operating system. */
export type ResolvedTheme = 'light' | 'dark';

/**
 * Where the choice is remembered. Shared with `public/theme-init.js`, which reads the same key
 * before Angular exists — change one and the other stops agreeing.
 */
export const THEME_STORAGE_KEY = 'ma-theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * The theme, as a three-state choice with the operating system as the default.
 *
 * **Why the operating system is resolved here rather than by a media query.** The dark palette used
 * to live in `@media (prefers-color-scheme: dark)`. A manual override on top of that needs the dark
 * values to apply under two conditions at once — the media query *and* an attribute — and CSS has no
 * way to share one declaration block between them, so the values would exist twice and drift apart
 * the first time one of them was edited. Resolving the preference in TypeScript and writing a single
 * `data-theme` attribute keeps the dark palette in exactly one place in the stylesheet, which is the
 * property that matters: the two themes cannot disagree about a colour nobody remembered to change
 * in both.
 *
 * The cost is that the theme now depends on JavaScript. For an application that is a single-page
 * Angular bundle behind a login redirect, that dependency already existed.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);

  private readonly choiceSignal = signal<ThemeChoice>(storedChoice());
  /** Tracked separately so a change of the OS setting repaints a `system` user without a reload. */
  private readonly systemDark = signal(this.matchesDark());

  readonly choice = this.choiceSignal.asReadonly();

  readonly resolved = computed<ResolvedTheme>(() => {
    const choice = this.choiceSignal();
    if (choice !== 'system') {
      return choice;
    }
    return this.systemDark() ? 'dark' : 'light';
  });

  constructor() {
    this.watchSystem();
    // The init script has already put the right attribute on <html> before first paint; this keeps
    // it in step afterwards, and is what actually applies a click on the toggle.
    effect(() => this.apply(this.resolved()));
  }

  use(choice: ThemeChoice): void {
    this.choiceSignal.set(choice);
    try {
      if (choice === 'system') {
        // Absent, not "system": the stored value is the OVERRIDE, and no override is the default.
        localStorage.removeItem(THEME_STORAGE_KEY);
      } else {
        localStorage.setItem(THEME_STORAGE_KEY, choice);
      }
    } catch {
      // Private browsing or a full quota: the choice applies now and simply does not survive a
      // reload, which is a far better outcome than a theme switch that throws.
    }
  }

  private apply(theme: ResolvedTheme): void {
    this.document.documentElement.setAttribute('data-theme', theme);
  }

  private watchSystem(): void {
    const query = this.document.defaultView?.matchMedia?.(DARK_QUERY);
    // `change` on a MediaQueryList is what fires when the OS flips at sunset, which is exactly the
    // moment a `system` user expects the application to follow.
    query?.addEventListener?.('change', (event) => this.systemDark.set(event.matches));
  }

  private matchesDark(): boolean {
    return this.document.defaultView?.matchMedia?.(DARK_QUERY)?.matches ?? false;
  }
}

/**
 * The stored override, or `system` when there is none.
 *
 * Anything unrecognised counts as no override rather than as an error: the key is in localStorage
 * where a user or an old build could have left anything, and the worst outcome of a bad value should
 * be the default theme. That covers the migration for free — the three-state control wrote the
 * literal `"system"`, which is now simply an unrecognised value and therefore means "no override",
 * exactly what it meant before.
 */
function storedChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}
