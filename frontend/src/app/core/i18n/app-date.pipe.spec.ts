import { describe, expect, it } from 'vitest';

import { AppDatePipe } from './app-date.pipe';

/**
 * Dates follow the interface language, not the browser's.
 *
 * The two views used to call `toLocaleDateString`, which answers to the *browser's* idea of a
 * locale: an English interface on a German machine printed German dates, and the format varied
 * between engines. These assertions pin both formats to the language the user chose.
 */
describe('AppDatePipe', () => {
  const pipe = new AppDatePipe();
  const iso = '2026-08-09T22:15:00+02:00';

  it('formats German as dd.MM.yyyy', () => {
    expect(pipe.transform(iso, 'de')).toBe('09.08.2026');
  });

  it('formats English as MM/dd/yyyy', () => {
    expect(pipe.transform(iso, 'en')).toBe('08/09/2026');
  });

  it('writes the year in full in both languages', () => {
    // `shortDate` would give `09.08.26` in German. A two-digit year on a maintenance record is the
    // one place ambiguity is not worth the characters it saves.
    expect(pipe.transform(iso, 'de')).toContain('2026');
    expect(pipe.transform(iso, 'en')).toContain('2026');
  });

  it('renders nothing for a missing date rather than the word "null"', () => {
    expect(pipe.transform(null, 'de')).toBe('');
    expect(pipe.transform(undefined, 'en')).toBe('');
    expect(pipe.transform('', 'de')).toBe('');
  });

  it('gives back what it was given when the value is not a date', () => {
    // A row with a broken timestamp should still show the reviewer what is stored, not
    // "Invalid Date" and not an exception that takes the table down.
    expect(pipe.transform('not-a-date', 'de')).toBe('not-a-date');
  });
});
