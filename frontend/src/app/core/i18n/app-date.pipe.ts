import { DatePipe, registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { Pipe, PipeTransform } from '@angular/core';

import { UiLanguage } from './dictionary';

/**
 * The date format each language actually expects.
 *
 * Written out rather than left to `'shortDate'`, which gives `dd.MM.yy` in German — a two-digit
 * year on a maintenance record is the one place ambiguity is not worth the four characters it
 * saves. Both patterns are the everyday civil format of their language, not an ISO compromise
 * neither side reads naturally.
 */
const PATTERNS: Readonly<Record<UiLanguage, string>> = {
  de: 'dd.MM.yyyy',
  en: 'MM/dd/yyyy',
};

/**
 * The same day, with the clock time on it.
 *
 * <p>Added for the protocol viewer's history section, where the day alone is not enough: two
 * corrections on one afternoon are a different story from two a month apart, and a ledger that
 * showed both as "14.08.2026" would hide the sequence a reader is there to reconstruct. The
 * moderation table keeps the day-only format — a column of timestamps is noise in a list somebody
 * is scanning for a title.
 *
 * <p>24-hour for German and 12-hour with am/pm for English, because that is what each language reads
 * without converting in their head. No seconds: the ledger records an act by a person, and nobody
 * needs to know which second they pressed the button.
 */
const PATTERNS_WITH_TIME: Readonly<Record<UiLanguage, string>> = {
  de: "dd.MM.yyyy, HH:mm 'Uhr'",
  en: 'MM/dd/yyyy, h:mm a',
};

/** Which locale Angular formats with. `de` is registered in `app.config.ts`; `en-US` is built in. */
const LOCALES: Readonly<Record<UiLanguage, string>> = {
  de: 'de',
  en: 'en-US',
};

/**
 * A date, in the format the interface language uses.
 *
 * <p><b>Why the language is an argument rather than something the pipe reads.</b> The obvious
 * shape is to inject {@link I18nService} and read the signal inside `transform` — and it produces a
 * pipe that goes stale. A pure pipe re-runs only when its *inputs* change, and switching language
 * changes no input on a table cell, so half the screen would keep the old format until something
 * else happened to redraw it. Passing `language()` makes the language an input, which is what
 * Angular needs to know the answer changed. The alternative, `pure: false`, hides the same
 * dependency behind a pipe that recomputes on every change-detection pass forever.
 *
 * <p>It delegates to Angular's own {@link DatePipe} rather than to `toLocaleDateString`, which is
 * what the two views did before: the browser's implementation answers to the *browser's* idea of a
 * locale and quietly varies between engines, while this is the same formatter the rest of the
 * framework uses and is pinned to registered locale data.
 *
 * <p>Invalid or missing input returns what it was given rather than "Invalid Date". A row with a
 * broken timestamp should still show the reviewer what is stored.
 */
@Pipe({ name: 'appDate' })
export class AppDatePipe implements PipeTransform {
  /**
   * @param withTime whether to add the clock time. Optional and off, so every existing call site
   *                 keeps the day-only format it was written for — the viewer's history is the one
   *                 place that needs the minute, and a default that changed under twenty call sites
   *                 would be a formatting change disguised as a feature.
   */
  transform(
    value: string | Date | null | undefined,
    language: UiLanguage,
    withTime = false,
  ): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    try {
      const pattern = withTime ? PATTERNS_WITH_TIME[language] : PATTERNS[language];
      const formatted = DATES.transform(value, pattern, undefined, LOCALES[language]);
      return formatted ?? String(value);
    } catch {
      // DatePipe THROWS on a value it cannot parse rather than returning null. A row with a broken
      // timestamp should still show the reviewer what is stored — an unparseable date is one bad
      // cell, and letting it escape here would take the whole table down with it.
      return String(value);
    }
  }
}

/**
 * Constructed rather than injected, deliberately.
 *
 * <p>`DatePipe` is not provided anywhere by default, so injecting it would mean a provider in
 * `app.config.ts` *and* in every `TestBed` that renders a table — a component would fail at runtime
 * with NG0201 for a formatting detail nobody would think to wire up. It holds no state and takes no
 * dependency beyond a default locale, which every call here overrides anyway.
 */
// German date data. Angular ships only `en-US`; every other locale is opt-in, and a DatePipe asked
// to format as `de` without this THROWS rather than falling back — it would take out the whole
// table rather than one cell. Registered beside the only thing that needs it rather than in
// app.config.ts, so a test that renders a date does not have to know this exists.
registerLocaleData(localeDe);

const DATES = new DatePipe('en-US');
