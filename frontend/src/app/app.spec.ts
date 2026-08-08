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
  /** Builds the shell with a stubbed identity, so no OIDC library is involved. */
  async function render(roles: string[], authenticated = true) {
    TestBed.resetTestingModule();
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
            logout: vi.fn(),
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
  });
});
