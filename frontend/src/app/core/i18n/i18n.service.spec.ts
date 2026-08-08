import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { DE, EN } from './dictionary';
import { I18nService } from './i18n.service';

describe('I18nService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('switches the dictionary and remembers the choice', () => {
    const service = TestBed.inject(I18nService);

    service.use('en');

    expect(service.t().nav.search).toBe(EN.nav.search);
    expect(localStorage.getItem('maintenance-assistant.language')).toBe('en');
  });

  it('restores a remembered choice on the next visit', () => {
    localStorage.setItem('maintenance-assistant.language', 'en');
    TestBed.resetTestingModule();

    expect(TestBed.inject(I18nService).language()).toBe('en');
  });

  it('toggles between the two languages', () => {
    const service = TestBed.inject(I18nService);
    service.use('de');

    service.toggle();
    expect(service.language()).toBe('en');

    service.toggle();
    expect(service.language()).toBe('de');
    expect(service.t().nav.search).toBe(DE.nav.search);
  });

  it('carries the same keys in both dictionaries', () => {
    // The types already guarantee this at compile time; asserting it at runtime catches a key
    // added with `as Dictionary` somewhere and a reader who cannot read the other language
    // finding a blank label.
    expect(keysOf(DE)).toEqual(keysOf(EN));
  });

  it('names the two answer modes the way DECISIONS.txt names them', () => {
    // The badge wording is the anti-hallucination mechanism's visible half; it should not drift
    // away from the vocabulary the rest of the project uses.
    expect(DE.modeA.badge).toBe('Belegte Antwort');
    expect(DE.modeB.badge).toContain('Allgemeiner Vorschlag');
    expect(DE.modeB.badge).toContain('keine Quelle im Bestand');
  });
});

/** Every key path in an object, sorted — so two dictionaries can be compared as sets. */
function keysOf(value: object, prefix = ''): string[] {
  return Object.entries(value)
    .flatMap(([key, child]) =>
      child !== null && typeof child === 'object'
        ? keysOf(child as object, `${prefix}${key}.`)
        : [`${prefix}${key}`],
    )
    .sort();
}
