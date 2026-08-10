import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { THEME_STORAGE_KEY, ThemeService } from './theme.service';

/**
 * The theme has to survive a reload and has to keep following the operating system for anyone who
 * did not override it. Both of those are storage and media-query behaviour rather than colour, so
 * they are asserted here; what the colours actually are is the stylesheet's business.
 */
describe('ThemeService', () => {
  /** The `change` listeners the service registered, so a test can flip the OS preference. */
  let listeners: ((event: { matches: boolean }) => void)[];

  /**
   * Installs a matchMedia that answers `matches` and records its listeners.
   *
   * Stubbed rather than spied on: jsdom does not implement matchMedia at all, so there is no
   * function to spy on — which is also why the service treats a missing matchMedia as "not dark"
   * instead of assuming the API exists.
   */
  function mockSystemDark(matches: boolean) {
    listeners = [];
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches,
      media: query,
      addEventListener: (_: string, handler: (event: { matches: boolean }) => void) =>
        listeners.push(handler),
      removeEventListener: () => {},
    }));
  }

  /**
   * Constructs the service and lets its effect flush, which is what application startup does: the
   * attribute is written by an effect, and an effect that has not been flushed has written nothing.
   */
  function create(): ThemeService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [ThemeService] });
    const service = TestBed.inject(ThemeService);
    TestBed.tick();
    return service;
  }

  /** What the service actually wrote on <html> — the single thing the stylesheet reads. */
  function applied(): string | null {
    return document.documentElement.getAttribute('data-theme');
  }

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    mockSystemDark(false);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('applies a theme even where matchMedia does not exist', () => {
    vi.unstubAllGlobals();

    // jsdom is that browser, and an ancient WebView would be another. A missing media API must cost
    // the OS preference, not the theme.
    expect(() => create()).not.toThrow();
    expect(applied()).toBe('light');
  });

  it('follows the operating system when nothing has been chosen', () => {
    mockSystemDark(true);
    const service = create();

    expect(service.choice()).toBe('system');
    expect(service.resolved()).toBe('dark');
    expect(applied()).toBe('dark');
  });

  it('overrides the operating system when a theme is chosen', () => {
    mockSystemDark(true);
    const service = create();

    service.use('light');
    TestBed.tick();

    // The whole point of the control: the OS says dark and the user says light, and the user wins.
    expect(service.resolved()).toBe('light');
    expect(applied()).toBe('light');
  });

  it('remembers a manual choice across a reload', () => {
    create().use('dark');

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    // A second construction is what a reload amounts to for this service.
    const reloaded = create();
    expect(reloaded.choice()).toBe('dark');
    expect(applied()).toBe('dark');
  });

  it('stores no value for "system", because the stored value IS the override', () => {
    const service = create();
    service.use('dark');
    service.use('system');

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(create().choice()).toBe('system');
  });

  it('follows the operating system as it changes, for a user who did not override it', () => {
    const service = create();
    expect(service.resolved()).toBe('light');

    listeners.forEach((handler) => handler({ matches: true }));
    TestBed.tick();

    // The OS flipping at dusk is exactly the moment a "system" user expects to be followed.
    expect(service.resolved()).toBe('dark');
    expect(applied()).toBe('dark');
  });

  it('ignores the operating system changing under a manual choice', () => {
    const service = create();
    service.use('light');
    TestBed.tick();

    listeners.forEach((handler) => handler({ matches: true }));
    TestBed.tick();

    expect(service.resolved()).toBe('light');
    expect(applied()).toBe('light');
  });

  it('treats an unrecognised stored value as no override rather than as an error', () => {
    // The key lives in localStorage, where a user or an older build could have left anything.
    localStorage.setItem(THEME_STORAGE_KEY, 'solarized');

    expect(create().choice()).toBe('system');
  });

  it('still applies a theme when storage cannot be written', () => {
    const service = create();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });

    // Private browsing must cost the persistence, not the theme switch.
    expect(() => service.use('dark')).not.toThrow();
    TestBed.tick();
    expect(applied()).toBe('dark');
  });
});
