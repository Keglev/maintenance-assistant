import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from './app';
import { AuthService } from './core/auth/auth.service';
import { I18nService } from './core/i18n/i18n.service';

/**
 * The shell decides two things worth asserting: which navigation a role is offered, and whether the
 * language switch actually changes the labels. Both are presentation — the backend enforces the
 * roles and pins the answer language — but a door that opens onto a 403 is still a defect.
 */
describe('App', () => {
  let logout: ReturnType<typeof vi.fn>;

  /** Builds the shell with a stubbed identity, so no OIDC library is involved. */
  async function render(roles: string[], authenticated = true) {
    TestBed.resetTestingModule();
    logout = vi.fn();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => authenticated,
            username: () => 'demo',
            realmRoles: () => roles,
            // The settings dialog reads this; a fixed instant keeps the assertion stable.
            signedInAt: () => new Date(2026, 7, 10, 21, 14),
            logout,
          },
        },
      ],
    }).compileComponents();

    TestBed.inject(I18nService).use('de');
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture;
  }

  beforeEach(() => localStorage.clear());

  it('creates the app shell', async () => {
    const fixture = await render(['operator']);

    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the application title in the header', async () => {
    const fixture = await render(['operator']);

    const header = (fixture.nativeElement as HTMLElement).querySelector('.app-title');

    expect(header?.textContent).toContain('Wartungsassistent');
  });

  it('offers the upload view to a schichtleiter', async () => {
    const fixture = await render(['schichtleiter']);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="nav-upload"]'),
    ).not.toBeNull();
  });

  it('hides the upload view from an operator and from a techniker', async () => {
    for (const role of ['operator', 'techniker']) {
      const fixture = await render([role]);
      const element = fixture.nativeElement as HTMLElement;

      // Presentation, not protection — but a role that cannot upload should not be shown a door
      // that opens onto a 403.
      expect(element.querySelector('[data-testid="nav-upload"]')).toBeNull();
      expect(element.querySelector('[data-testid="nav-search"]')).not.toBeNull();
    }
  });

  it('shows no navigation at all before sign-in', async () => {
    const fixture = await render([], false);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="nav-search"]'),
    ).toBeNull();
  });

  it('switches the interface language without a reload', async () => {
    const fixture = await render(['operator']);
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="lang-en"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="nav-search"]')?.textContent).toContain('Search');
    expect(element.querySelector('.app-title')?.textContent).toContain('Maintenance Assistant');
    expect(element.querySelector('[data-testid="footer-docs"]')?.textContent).toContain(
      'Documentation',
    );
  });

  it('names the role next to the user, so two demo logins are told apart', async () => {
    const fixture = await render(['techniker']);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="header-role"]')
        ?.textContent,
    ).toContain('Techniker');
  });

  it('shows the most privileged role a user holds, not the first one', async () => {
    // Keycloak hands out `default-roles-maintenance` and friends alongside the real one, and a
    // Schichtleiter labelled "Bediener" would make the upload link look like a bug.
    const fixture = await render(['offline_access', 'operator', 'schichtleiter']);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="header-role"]')
        ?.textContent,
    ).toContain('Schichtleiter');
  });

  it('links out to the docs, the repository and the AI-usage statement', async () => {
    const fixture = await render(['operator']);
    const element = fixture.nativeElement as HTMLElement;

    for (const id of ['footer-docs', 'footer-repo', 'footer-ai-usage']) {
      const link = element.querySelector(`[data-testid="${id}"]`) as HTMLAnchorElement;
      expect(link.getAttribute('href')).toMatch(/^https:\/\//);
      // A new tab must not get a handle on the tab holding the session.
      expect(link.getAttribute('rel')).toContain('noopener');
    }
  });

  it('asks before signing out, and does not sign out until it is confirmed', async () => {
    const fixture = await render(['techniker']);
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="sign-out"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // The button sits in a toolbar on a tablet operated with work gloves. A mis-tap that ends the
    // session in the middle of a fault costs a full Keycloak round trip.
    expect(element.querySelector('[data-testid="sign-out-confirm-dialog"]')).not.toBeNull();
    expect(logout).not.toHaveBeenCalled();

    (element.querySelector('[data-testid="sign-out-confirm"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(logout).toHaveBeenCalled();
  });

  it('leaves the session alone when the confirmation is cancelled', async () => {
    const fixture = await render(['techniker']);
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="sign-out"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    (element.querySelector('[data-testid="sign-out-cancel"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="sign-out-confirm-dialog"]')).toBeNull();
    expect(logout).not.toHaveBeenCalled();
  });

  it('closes the sign-out confirmation on Escape without signing out', async () => {
    const fixture = await render(['techniker']);
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="sign-out"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    element
      .querySelector('[data-testid="sign-out-confirm-backdrop"]')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();

    // Escape is the universal "no" and must never be the one that ends the session.
    expect(element.querySelector('[data-testid="sign-out-confirm-dialog"]')).toBeNull();
    expect(logout).not.toHaveBeenCalled();
  });

  it('offers the theme control in the header, signed in and signed out alike', async () => {
    for (const authenticated of [true, false]) {
      const fixture = await render(['operator'], authenticated);

      // Screenshots are taken logged out too, and the header renders in both states — so one
      // control in the shell covers both rather than two controls that would appear together.
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="theme-toggle"]'),
      ).not.toBeNull();
    }
  });

  it('opens the settings dialog from the gear, signed in and signed out alike', async () => {
    for (const authenticated of [true, false]) {
      const fixture = await render(['techniker'], authenticated);
      const element = fixture.nativeElement as HTMLElement;

      (element.querySelector('[data-testid="settings-button"]') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(element.querySelector('[data-testid="settings-dialog"]')).not.toBeNull();
      // The version is read from package.json at build time, so it is never a stale constant.
      expect(element.querySelector('[data-testid="settings-version"]')?.textContent?.trim()).toMatch(
        /^\d+\.\d+\.\d+/,
      );
      // The account rows exist only when there is an account.
      expect(element.querySelector('[data-testid="settings-account"]') !== null).toBe(authenticated);
    }
  });

  it('names who is signed in and since when', async () => {
    const fixture = await render(['schichtleiter']);
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="settings-button"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="settings-account"]')?.textContent).toContain('demo');
    expect(element.querySelector('[data-testid="settings-role"]')?.textContent).toContain(
      'Schichtleiter',
    );
    expect(element.querySelector('[data-testid="settings-since"]')?.textContent).toContain('21:14');
  });

  it('scales the whole interface from the settings dialog, and remembers it', async () => {
    const fixture = await render(['operator']);
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="settings-button"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    (element.querySelector('[data-testid="font-xl"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // One attribute on <html>: the design system is rem-based, so this moves type, spacing and the
    // 44 px tap targets together rather than only the letters.
    expect(document.documentElement.getAttribute('data-font')).toBe('xl');
    expect(localStorage.getItem('ma-font')).toBe('xl');

    (element.querySelector('[data-testid="font-normal"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // Normal is the ABSENCE of the attribute, and of the stored value.
    expect(document.documentElement.hasAttribute('data-font')).toBe(false);
    expect(localStorage.getItem('ma-font')).toBeNull();
  });

  it('offers help before sign-in, because the colours need explaining first', async () => {
    const fixture = await render([], false);
    const element = fixture.nativeElement as HTMLElement;

    const help = element.querySelector('[data-testid="help-button"]') as HTMLButtonElement;
    expect(help).not.toBeNull();

    help.click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="help-dialog"]')).not.toBeNull();
  });
});
