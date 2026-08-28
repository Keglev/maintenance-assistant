import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

/** The three steps offered. `normal` is the absence of an attribute, not a value of it. */
export type FontScale = 'normal' | 'lg' | 'xl';

/**
 * Where the choice is remembered. Shared with `public/theme-init.js`, which reads the same key
 * before Angular exists — change one and the other stops agreeing.
 */
const FONT_STORAGE_KEY = 'ma-font';

const SCALES: readonly FontScale[] = ['normal', 'lg', 'xl'];

/**
 * Application-wide font scaling, in three steps.
 *
 * **Why this is one attribute and not a stylesheet.** The design system is rem-based throughout —
 * every spacing step, every type size, every touch target — so moving the root font size moves the
 * whole interface together, including the 44 px tap targets, which is the half that matters on a
 * tablet. A second set of sizes would be a second design system to keep in step with the first.
 *
 * The audience is stated rather than assumed: this application is read on small machinery-mounted
 * screens by technicians who are frequently not twenty-five. Browser zoom does the same job and is
 * the wrong answer here — it is per-origin, it is buried in a menu on a kiosk browser, and on a
 * locked-down shop-floor tablet the user often cannot reach it at all.
 */
@Injectable({ providedIn: 'root' })
export class FontScaleService {
  private readonly document = inject(DOCUMENT);

  private readonly current = signal<FontScale>(storedScale());

  readonly scale = this.current.asReadonly();

  constructor() {
    effect(() => this.apply(this.current()));
  }

  use(scale: FontScale): void {
    this.current.set(scale);
    try {
      if (scale === 'normal') {
        // Absent, not "normal": the stored value is the DEPARTURE from the default.
        localStorage.removeItem(FONT_STORAGE_KEY);
      } else {
        localStorage.setItem(FONT_STORAGE_KEY, scale);
      }
    } catch {
      // Private browsing or a full quota: the size applies now and does not survive a reload, which
      // is better than a settings dialog that throws.
    }
  }

  private apply(scale: FontScale): void {
    const root = this.document.documentElement;
    if (scale === 'normal') {
      root.removeAttribute('data-font');
    } else {
      root.setAttribute('data-font', scale);
    }
  }
}

/** Anything unrecognised is the default rather than an error — the key lives in localStorage. */
function storedScale(): FontScale {
  try {
    const stored = localStorage.getItem(FONT_STORAGE_KEY) as FontScale | null;
    return stored && SCALES.includes(stored) ? stored : 'normal';
  } catch {
    return 'normal';
  }
}
