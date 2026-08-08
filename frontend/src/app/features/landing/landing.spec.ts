import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Landing } from './landing';

/**
 * The landing page is the ninety seconds a recruiter gives this project, so what is asserted is
 * what has to survive an edit: the problem is stated, the demo credentials are on screen, the three
 * seeded questions are offered, and the sign-in button starts the real redirect.
 */
describe('Landing', () => {
  let login: ReturnType<typeof vi.fn>;
  let authenticated: boolean;

  /** Configures the module with a stubbed identity, without creating the component yet. */
  async function configure(language: 'de' | 'en' = 'de') {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [Landing],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { isAuthenticated: () => authenticated, login },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18nService).use(language);
  }

  async function render(language: 'de' | 'en' = 'de') {
    await configure(language);
    const fixture = TestBed.createComponent(Landing);
    await fixture.whenStable();
    return fixture;
  }

  beforeEach(() => {
    login = vi.fn();
    authenticated = false;
    localStorage.clear();
  });

  it('starts the Keycloak redirect when the sign-in button is clicked', async () => {
    const fixture = await render();

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="sign-in"]',
      ) as HTMLButtonElement
    ).click();

    expect(login).toHaveBeenCalled();
  });

  it('sends an already authenticated visitor straight to the search view', async () => {
    authenticated = true;
    await configure();
    // Spied before the component exists: the redirect fires from a constructor effect, and a real
    // navigation into an empty route table would reject in the background.
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const fixture = TestBed.createComponent(Landing);
    await fixture.whenStable();

    // Someone who is signed in came to ask a question, not to read the pitch again.
    expect(navigate).toHaveBeenCalledWith(['/search']);
  });

  it('names the four demo users and the shared password', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const demo = element.querySelector('[data-testid="demo-users"]') as HTMLElement;

    for (const user of ['operator', 'techniker', 'schichtleiter', 'admin']) {
      expect(demo.textContent).toContain(user);
    }
    // Without the password the demo accounts are a list of names, and the visit ends here.
    expect(element.querySelector('[data-testid="demo-password"]')?.textContent).toContain(
      'demo1234',
    );
  });

  it('offers the three seeded example questions', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    const examples = element.querySelectorAll('[data-testid="landing-example"]');
    expect(examples.length).toBe(3);
    // The three demo seeds: several sources, cross-language retrieval, and the Mode-B gap.
    expect(element.textContent).toContain('E-47');
    expect(element.textContent).toContain('FB-04');
    expect(element.textContent).toContain('AB-02');
  });

  it('states the problem before the technology', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('.hero-problem')?.textContent).toContain('Nachts');
  });

  it('switches the pitch to English with the interface language', async () => {
    const fixture = await render('en');
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('h1')?.textContent).toContain(
      'Maintenance knowledge that does not go home',
    );
    // The example questions stay German: they have to retrieve something from a German corpus.
    expect(element.querySelector('.example-question')?.getAttribute('lang')).toBe('de');
  });
});
