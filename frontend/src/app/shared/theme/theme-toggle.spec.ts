import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nService } from '../../core/i18n/i18n.service';
import { THEME_STORAGE_KEY } from '../../core/theme/theme.service';
import { ThemeToggle } from './theme-toggle';

/**
 * Two buttons, and the operating system as the silent default until one of them is pressed. What is
 * asserted is that the control always says which theme is ON SCREEN — with nothing stored that is
 * the OS's answer, and a control showing neither half pressed would look broken on a first visit.
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

  function pressed(element: HTMLElement, id: string) {
    return element.querySelector(`[data-testid="${id}"]`)?.getAttribute('aria-pressed');
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

  it('offers exactly two states — the system is the default, not a button', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelectorAll('.theme-switch button').length).toBe(2);
    expect(element.querySelector('[data-testid="theme-system"]')).toBeNull();
  });

  it('shows the operating system theme as the active one before any choice is made', async () => {
    mockSystemDark(true);
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // Nothing is stored, so nothing was "chosen" — but dark IS what is on screen, and the control
    // has to say so rather than show two unpressed buttons.
    expect(pressed(element, 'theme-dark')).toBe('true');
    expect(pressed(element, 'theme-light')).toBe('false');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });

  it('stores an explicit choice that then beats the operating system', async () => {
    mockSystemDark(true);
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="theme-light"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    expect(pressed(element, 'theme-light')).toBe('true');
  });

  it('applies and records the chosen theme', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="theme-dark"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
  });

  /**
   * MIGRATION. The three-state control wrote the literal "system" into the same key. It must read
   * as "no override" — a stored value the new control cannot express is not an error state.
   */
  it('treats a stored "system" from the old control as no choice at all', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'system');
    mockSystemDark(true);
    const fixture = await render();

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(pressed(fixture.nativeElement as HTMLElement, 'theme-dark')).toBe('true');
  });

  it('names both buttons, having no visible text to fall back on', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="theme-toggle"]')?.getAttribute('aria-label')).toBe(
      'Darstellung',
    );
    for (const [id, name] of [
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

    expect(icons.length).toBe(2);
    icons.forEach((icon) => expect(icon.getAttribute('aria-hidden')).toBe('true'));
  });

  it('translates its labels with the interface language', async () => {
    const fixture = await render('en');

    expect(
      (fixture.nativeElement as HTMLElement)
        .querySelector('[data-testid="theme-light"]')
        ?.getAttribute('title'),
    ).toBe('Light');
  });
});
