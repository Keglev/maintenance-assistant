import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nService } from '../../core/i18n/i18n.service';
import { THEME_STORAGE_KEY } from '../../core/theme/theme.service';
import { ThemeToggle } from './theme-toggle';

/**
 * Three states, and the control has to say which one is active — a segmented control whose active
 * segment is only a colour tells a screen-reader user nothing, and "system" looking the same as
 * "light" is what makes someone press it twice.
 */
describe('ThemeToggle', () => {
  /** Stubbed rather than spied on: jsdom implements no matchMedia to spy on. */
  function mockSystemDark(matches: boolean) {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }));
  }

  async function render(language: 'de' | 'en' = 'de') {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({ imports: [ThemeToggle] }).compileComponents();
    TestBed.inject(I18nService).use(language);

    const fixture = TestBed.createComponent(ThemeToggle);
    await fixture.whenStable();
    return fixture;
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

  it('offers all three states, with system active by default', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    for (const state of ['theme-system', 'theme-light', 'theme-dark']) {
      expect(element.querySelector(`[data-testid="${state}"]`)).not.toBeNull();
    }
    expect(element.querySelector('[data-testid="theme-system"]')?.getAttribute('aria-pressed')).toBe(
      'true',
    );
  });

  it('applies and records the chosen theme', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="theme-dark"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(element.querySelector('[data-testid="theme-dark"]')?.getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(element.querySelector('[data-testid="theme-system"]')?.getAttribute('aria-pressed')).toBe(
      'false',
    );
  });

  it('goes back to following the operating system', async () => {
    mockSystemDark(true);
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="theme-light"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    // Returning to "system" is a real choice, not a reset: the OS says dark, so dark comes back.
    (element.querySelector('[data-testid="theme-system"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });

  /**
   * The buttons show icons and no text, so aria-label and title are not decoration: they are the
   * only name this control has, for a screen reader and for a hover respectively.
   */
  it('names the group and every button, having no visible text to fall back on', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="theme-toggle"]')?.getAttribute('aria-label')).toBe(
      'Darstellung',
    );
    for (const [id, name] of [
      ['theme-system', 'System'],
      ['theme-light', 'Hell'],
      ['theme-dark', 'Dunkel'],
    ]) {
      const button = element.querySelector(`[data-testid="${id}"]`) as HTMLElement;
      expect(button.getAttribute('aria-label')).toBe(name);
      expect(button.getAttribute('title')).toBe(name);
      expect(button.textContent?.trim()).toBe('');
    }
  });

  it('draws its icons inline, with nothing for the CSP to allow', async () => {
    const fixture = await render();
    const icons = (fixture.nativeElement as HTMLElement).querySelectorAll('.theme-switch svg');

    expect(icons.length).toBe(3);
    // Decorative: the button already carries the name, and an announced icon would double it.
    icons.forEach((icon) => expect(icon.getAttribute('aria-hidden')).toBe('true'));
  });

  it('translates its labels with the interface language', async () => {
    const fixture = await render('en');
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="theme-toggle"]')?.getAttribute('aria-label')).toBe(
      'Appearance',
    );
    expect(element.querySelector('[data-testid="theme-light"]')?.getAttribute('title')).toBe(
      'Light',
    );
  });
});
