import { Injectable, computed, signal } from '@angular/core';

import { DICTIONARIES, Dictionary, UiLanguage } from './dictionary';

/** Where the choice is remembered between visits. */
const STORAGE_KEY = 'maintenance-assistant.language';

/**
 * The DE/EN switch, as a runtime dictionary rather than Angular's build-time i18n.
 *
 * Angular's `$localize` produces one bundle per locale, which means two builds, two images and a
 * routing or host decision to pick between them. For two languages and one small application that
 * is a deployment problem bought to solve a string problem — and it would make the language a
 * property of the URL rather than of the person, so switching would reload the page and lose the
 * answer on screen. A signal holding one of two typed objects switches instantly and ships one
 * bundle.
 *
 * **The UI language is not the answer language.** The backend pins every answer to the language of
 * the *question* (ADR-002's language rule), and nothing is translated anywhere in this system by
 * decision. So a German answer stays German with the interface in English: this service changes
 * labels, never content. The rendered answer carries its own `lang` attribute for exactly that
 * reason.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly current = signal<UiLanguage>(initialLanguage());

  /** The active language, for `lang` attributes and for the switch's own state. */
  readonly language = this.current.asReadonly();

  /** The active dictionary. Components read `t().search.submit` and re-render on a switch. */
  readonly t = computed<Dictionary>(() => DICTIONARIES[this.current()]);

  use(language: UiLanguage): void {
    this.current.set(language);
    try {
      localStorage.setItem(STORAGE_KEY, language);
    } catch {
      // Private browsing or a storage quota: the choice simply does not survive a reload,
      // which is a far better outcome than a view that fails to render.
    }
  }

  toggle(): void {
    this.use(this.current() === 'de' ? 'en' : 'de');
  }
}

/**
 * A remembered choice wins; otherwise the browser decides.
 *
 * German is the fallback rather than English because the plant, the corpus and the demo users are
 * German — the setting this models is a German shop floor, and a visitor whose browser says nothing
 * useful is better served by the language the protocols are written in.
 */
function initialLanguage(): UiLanguage {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'de' || stored === 'en') {
      return stored;
    }
  } catch {
    // Unreadable storage is not an error worth surfacing; fall through to the browser.
  }
  const preferred = typeof navigator === 'undefined' ? '' : (navigator.language ?? '');
  return preferred.toLowerCase().startsWith('en') ? 'en' : 'de';
}
