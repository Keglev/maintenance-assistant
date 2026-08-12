import { KEYCLOAK_URL } from './guard';
import { contrastReport, expect, selectSearchMachine, signIn, test } from './support';

/**
 * THE #47 REGRESSION, and the one thing a rendered runner buys that jsdom cannot.
 *
 * The defect: the realm heading above the login card rendered white on a near-white canvas —
 * measured 1.09:1, which is not "hard to read", it is invisible. Every code-level check passed,
 * and passed honestly: the element was ABSENT from the theme's stylesheet. It inherited a colour
 * the parent theme had chosen for a dark photographic backdrop that this theme removes. Reading a
 * stylesheet cannot find a rule that is not in it, and jsdom has no layout engine, no cascade worth
 * trusting and no concept of what is painted behind what.
 *
 * So these tests do the only thing that could have caught it: ask the browser what colour the text
 * ended up, ask what it is painted on, and do the arithmetic. Both palettes, because #47's heading
 * was correct in dark BY ACCIDENT for as long as the defect existed — one scheme reading correctly
 * proves nothing about the other.
 */

/** WCAG AA for body text. Large text may sit at 3.0; nothing asserted here relies on that. */
const AA_NORMAL = 4.5;

/** Both schemes, run as a matrix rather than as two copies of the same test. */
const SCHEMES = ['light', 'dark'] as const;

for (const scheme of SCHEMES) {
  test.describe(`contrast — ${scheme} scheme`, () => {
    test.use({ colorScheme: scheme });

    test('the login realm heading is legible', async ({ page }) => {
      await page.goto('/');
      await page.getByTestId('sign-in').click();
      await page.waitForURL(`${KEYCLOAK_URL}/**`);
      await expect(page.locator('#kc-header-wrapper')).toBeVisible();

      const heading = await contrastReport(page, '#kc-header-wrapper');

      expect(
        heading.ratio,
        `#kc-header-wrapper is ${heading.color} on ${heading.background} = ` +
          `${heading.ratio.toFixed(2)}:1 in the ${scheme} scheme. This is the #47 defect: it ` +
          `measured 1.09:1 in light, and the element is styled by the PARENT theme with ` +
          `!important, so it is only ours if this theme says so explicitly.`,
      ).toBeGreaterThanOrEqual(AA_NORMAL);
    });

    test('the demo box on the login page is legible', async ({ page }) => {
      await page.goto('/');
      await page.getByTestId('sign-in').click();
      await page.waitForURL(`${KEYCLOAK_URL}/**`);

      // The demo box is how a visitor gets in at all, so unreadable here costs a demo.
      const box = await contrastReport(page, '.wa-demo');
      expect(box.ratio, `.wa-demo is ${box.color} on ${box.background}`).toBeGreaterThanOrEqual(
        AA_NORMAL,
      );
    });

    test('the application surfaces a signed-in user reads are legible', async ({ page }) => {
      await signIn(page, 'techniker');
      // `signIn` waits for the identity in the header, which arrives before the ROUTED VIEW has
      // rendered. Measuring a colour on an element that does not exist yet is the one race this
      // file can have, so the readiness condition is stated rather than hoped for.
      await expect(page.locator('.page-title')).toBeVisible();

      // Chosen as the surfaces where a token colour is most likely to be wrong on one palette only:
      // a heading, a form label, the muted text in the chrome, and the help panel's secondary ink —
      // the last one because "muted" is the token most likely to be legible on one ground and not
      // on the other, which is the exact shape of the #47 defect.
      const surfaces = [
        { selector: '.page-title', what: 'the view heading' },
        { selector: '.field-label', what: 'a form label' },
        { selector: '[data-testid="header-username"]', what: 'the signed-in username' },
        { selector: '.app-tagline', what: 'the header tagline (faint ink)' },
        { selector: '.search-help', what: 'the help panel (muted ink on the canvas)' },
      ];

      for (const surface of surfaces) {
        const measured = await contrastReport(page, surface.selector);
        expect(
          measured.ratio,
          `${surface.what} (${surface.selector}) is ${measured.color} on ` +
            `${measured.background} = ${measured.ratio.toFixed(2)}:1 in ${scheme}`,
        ).toBeGreaterThanOrEqual(AA_NORMAL);
      }
    });

    /**
     * The v1.2 approval marker, measured rather than eyeballed.
     *
     * It is a new token pair (`--c-review-*`) on a surface it has never been on, and it is the one
     * element in this release whose entire job is to be read — an unapproved source that a
     * technician does not notice is decision 1 of 2026-08-11 failing silently. That is the #47
     * shape waiting to happen: a colour correct on one palette and not the other, with nothing
     * wrong in the stylesheet to look at.
     *
     * The APPROVED case is measured too, and it is the likelier of the two to fail: it is muted ink
     * on purpose, and "unobtrusive" is one step away from "below AA".
     */
    test('the approval markers on a source card are legible', async ({ page }) => {
      await signIn(page, 'techniker');
      await page.route('**/api/query', (route) =>
        route.fulfill({
          json: {
            mode: 'A',
            language: 'de',
            answer: 'Der Druckabfall entsteht am Druckbegrenzungsventil [P1]. Siehe auch [P2].',
            claims: [{ text: 'Ventil pruefen', label: 'P1' }],
            citations: [
              {
                label: 'P1',
                protocolId: '0f9c5b02-0000-4000-8000-000000000001',
                title: 'E-47 Druckabfall im Presshub',
                errorCode: 'E-47',
                incidentDate: '2026-03-14',
                similarity: 0.69,
                approved: true,
              },
              {
                label: 'P2',
                protocolId: '0f9c5b02-0000-4000-8000-000000000002',
                title: 'E-47 nach Programmwechsel',
                errorCode: 'E-47',
                incidentDate: '2026-04-02',
                similarity: 0.61,
                approved: false,
              },
            ],
          },
        }),
      );

      await selectSearchMachine(page, 'PR-03');
      await page.getByTestId('question-input').fill('Warum faellt der Druck im Presshub ab?');
      await page.getByTestId('ask-button').click();
      await expect(page.getByTestId('answer-unapproved')).toBeVisible();

      const surfaces = [
        { selector: '[data-approval="UNAPPROVED"]', what: 'the unapproved chip on a source card' },
        { selector: '[data-approval="APPROVED"]', what: 'the approved marker (muted by design)' },
        { selector: '[data-testid="answer-unapproved"]', what: 'the answer-level note' },
      ];

      for (const surface of surfaces) {
        const measured = await contrastReport(page, surface.selector);
        expect(
          measured.ratio,
          `${surface.what} (${surface.selector}) is ${measured.color} on ` +
            `${measured.background} = ${measured.ratio.toFixed(2)}:1 in ${scheme}`,
        ).toBeGreaterThanOrEqual(AA_NORMAL);
      }
    });

    /**
     * A DEFECT THIS SUITE FOUND IN EXISTING CODE, on its first complete run.
     *
     * `.footer-note` — the "Demo, synthetic data" line at the foot of every signed-in page — uses
     * `--c-ink-faint` (#7c8794), which measures 3.65:1 on the light surface. Below AA for body
     * text. The dark palette is fine at 6.1:1, so this is the #47 shape exactly: one scheme correct,
     * the other not, and nothing in the code to look at because the token is applied on purpose.
     *
     * NOT FIXED HERE, DELIBERATELY. This PR builds the test foundation; changing `--c-ink-faint`
     * touches the tagline, the eyebrow, `.optional` and disabled button text as well, and that is a
     * design pass with its own drill — it is parked on the v1.1.1 list in PROJECT-PHASES.
     *
     * `test.fail()` rather than a skip, and this is the point: the test RUNS, the ratio is measured
     * every time, and the run goes RED the day someone fixes the token. A known defect that cannot
     * rot into a forgotten one, and a marker that deletes itself by failing.
     */
    test('KNOWN DEFECT: the footer demo notice is below AA in light', async ({ page }) => {
      test.fail(
        scheme === 'light',
        '.footer-note is --c-ink-faint at 3.65:1 on the light surface. Parked as a v1.1.1 polish ' +
          'candidate; when it is fixed this test starts passing and must be deleted.',
      );

      await signIn(page, 'techniker');
      const measured = await contrastReport(page, '.footer-note');
      expect(
        measured.ratio,
        `.footer-note is ${measured.color} on ${measured.background} = ` +
          `${measured.ratio.toFixed(2)}:1 in ${scheme}`,
      ).toBeGreaterThanOrEqual(AA_NORMAL);
    });
  });
}
