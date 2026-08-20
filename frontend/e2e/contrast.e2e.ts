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
     * The correction dialog's approval warning — v1.2's third notice variant.
     *
     * New tokens on a new ground: `--c-review-*` had only ever been used on the surface and on a
     * chip, and this puts the same ink on a filled panel inside a modal, which is a different
     * background stack. That is the #47 shape — a colour correct on one palette and not the other,
     * with nothing wrong in the stylesheet to look at — and it is a sentence the reader must
     * actually read, because it is the only warning that an approval is about to be withdrawn.
     */
    test('the approval warning in the correction dialog is legible', async ({ page }) => {
      await signIn(page, 'schichtleiter');
      await page.getByTestId('nav-moderation').click();
      await expect(page.getByTestId('moderation-table')).toBeVisible();

      // The seeded 150 are APPROVED (`system:corpus-seed`), so the queue filter is the shortest
      // route to a protocol whose correction would cost an approval.
      await page.getByTestId('filter-approval').selectOption('APPROVED');
      await page.getByTestId('moderation-row').first().getByTestId('row-edit').click();
      await expect(page.getByTestId('edit-resets-approval')).toBeVisible();

      const measured = await contrastReport(page, '[data-testid="edit-resets-approval"]');
      expect(
        measured.ratio,
        `the approval warning is ${measured.color} on ${measured.background} = ` +
          `${measured.ratio.toFixed(2)}:1 in ${scheme}`,
      ).toBeGreaterThanOrEqual(AA_NORMAL);
    });

    /**
     * The duplicate-detection dialog — v1.2's last new surface, and three grounds in one shot.
     *
     * <p>Measured rather than eyeballed because the whole design turns on legibility: the sentence
     * has to be READ, or an approver treats the list as an obstacle and clicks past it. Three
     * different stacks are checked because each is new: the review-palette notice on a dialog
     * surface, a card title on `--c-sunken` (the token that was INVERTED between palettes once
     * already — recessed read as raised in dark), and the similarity percentage, which is
     * `--c-ink-muted` on that same recessed ground and is the smallest text in the dialog.
     */
    test('the duplicate-detection dialog is legible', async ({ page }) => {
      await signIn(page, 'admin');
      await expect(page.getByTestId('moderation-table')).toBeVisible();
      // Stubbed so the dialog opens whatever the corpus currently holds: this test is about the
      // colours the dialog paints, not about whether two real protocols happen to be similar.
      await page.route('**/similar', (route) =>
        route.fulfill({
          json: {
            comparable: true,
            total: 1,
            allIds: ['0f9c5b02-0000-4000-8000-000000000001'],
            threshold: 0.92,
            candidates: [
              {
                id: '0f9c5b02-0000-4000-8000-000000000001',
                title: 'E-47 Druckabfall im Presshub',
                incidentDate: '2024-10-08',
                uploadedBy: 'techniker',
                uploadedAt: '2026-08-07T08:00:00Z',
                similarity: 0.9778,
                approval: {
                  state: 'APPROVED',
                  approvedBy: 'admin',
                  approvedAt: '2026-08-11T09:00:00Z',
                },
              },
            ],
          },
        }),
      );

      await page.getByTestId('filter-approval').selectOption('UNAPPROVED');
      await page.getByTestId('row-approve').first().click();
      await expect(page.getByTestId('duplicates-dialog')).toBeVisible();

      for (const selector of [
        '[data-testid="duplicates-intro"]',
        '[data-testid="duplicate-card"] .duplicate-title',
        '[data-testid="duplicate-score"]',
        '[data-testid="duplicates-method"]',
      ]) {
        const measured = await contrastReport(page, selector);
        expect(
          measured.ratio,
          `${selector} is ${measured.color} on ${measured.background} = ` +
            `${measured.ratio.toFixed(2)}:1 in ${scheme}`,
        ).toBeGreaterThanOrEqual(AA_NORMAL);
      }
    });

    /**
     * The protocol viewer's history section — where v1.2's provenance lives since 2026-08-14.
     *
     * <p>Measured because this is the only place the corpus now says who approved a protocol and
     * when. Three of the four selectors are deliberately QUIET type — a heading in
     * `--c-ink-muted`, and an actor and a timestamp in monospace at `--t-sm` — and quiet is exactly
     * the register that slips below AA without anyone noticing, on a surface a reader is expected
     * to actually read rather than glance at.
     */
    test('the protocol history is legible', async ({ page }) => {
      await signIn(page, 'admin');
      await expect(page.getByTestId('moderation-table')).toBeVisible();
      // Stubbed so the section is fully populated whatever the corpus currently holds: this test is
      // about the colours it paints, not about which protocol happens to be first in the list.
      await page.route('**/history', (route) =>
        route.fulfill({
          json: {
            limit: 3,
            total: 4,
            events: [
              {
                action: 'APPROVE',
                actor: 'admin',
                comment: 'geprüft und freigegeben',
                at: '2026-08-14T09:30:00Z',
              },
            ],
          },
        }),
      );

      await page.getByTestId('row-open').first().click();
      await expect(page.getByTestId('protocol-history')).toBeVisible();

      for (const selector of [
        '[data-testid="protocol-history"] .viewer-history-heading',
        '[data-testid="protocol-history"] .viewer-history-what',
        '[data-testid="history-actor"]',
        '[data-testid="history-when"]',
        '[data-testid="history-comment"]',
        '[data-testid="history-more"]',
      ]) {
        const measured = await contrastReport(page, selector);
        expect(
          measured.ratio,
          `${selector} is ${measured.color} on ${measured.background} = ` +
            `${measured.ratio.toFixed(2)}:1 in ${scheme}`,
        ).toBeGreaterThanOrEqual(AA_NORMAL);
      }
    });

    /**
     * A DEFECT THIS SUITE FOUND IN EXISTING CODE, on its first complete run — AND CLOSED.
     *
     * `.footer-note` — the "Demo, synthetic data" line at the foot of every signed-in page —
     * measured 3.65:1 on the light surface. It carried a `test.fail()` marker from the day it was
     * found until the day it was fixed, so the ratio was measured on every run and the build was
     * always one token change away from going red. That day was 2026-08-20 and the marker is gone:
     * this is now a plain assertion, and a plain assertion is the only correct end state for one.
     *
     * WHAT THE FIX TURNED OUT TO BE, and why this test grew rather than simply losing its marker:
     * `.footer-note` was never one defect. `--c-ink-faint` is applied to five live-text surfaces
     * and ALL FIVE were below AA in the light palette — the footer note at 3.65:1, the landing
     * eyebrow at 3.34:1, the machine plate's labels at 3.05:1, `.optional` and `.example-machine`
     * at 3.65:1. Measuring one of them and calling the token fixed is how the other four would have
     * come back. So every consumer is measured here, on the ground it is actually painted on.
     *
     * The disabled surfaces are NOT here, and that is the token split rather than an omission:
     * `.btn[disabled]` and `input:disabled` moved to `--c-ink-disabled`, which is exempt under
     * WCAG 2.2 SC 1.4.3 (inactive user interface components) and where low contrast is the message.
     * Asserting AA on them would encode a requirement that does not exist and forbid one that does.
     */
    test('every faint-ink surface clears AA', async ({ page }) => {
      // The landing page first: three of the five consumers are only reachable signed out.
      await page.goto('/');
      await expect(page.locator('.eyebrow')).toBeVisible();

      for (const surface of [
        { selector: '.eyebrow', what: 'the landing eyebrow (on the canvas)' },
        { selector: '.plate-rows dt', what: 'a machine-plate label (on the plate gradient)' },
        { selector: '.example-machine', what: 'an example card machine label (on a card)' },
      ]) {
        const measured = await contrastReport(page, surface.selector);
        expect(
          measured.ratio,
          `${surface.what} (${surface.selector}) is ${measured.color} on ` +
            `${measured.background} = ${measured.ratio.toFixed(2)}:1 in ${scheme}`,
        ).toBeGreaterThanOrEqual(AA_NORMAL);
      }

      // Then the two that need a session: the footer note on every view, and the "(optional)" label
      // that only the upload form renders.
      await signIn(page, 'techniker');
      await page.getByTestId('nav-upload').click();
      await expect(page.locator('.optional')).toBeVisible();

      for (const surface of [
        { selector: '.footer-note', what: 'the footer demo notice (on the footer surface)' },
        { selector: '.optional', what: 'the "(optional)" field label (on the form card)' },
      ]) {
        const measured = await contrastReport(page, surface.selector);
        expect(
          measured.ratio,
          `${surface.what} (${surface.selector}) is ${measured.color} on ` +
            `${measured.background} = ${measured.ratio.toFixed(2)}:1 in ${scheme}`,
        ).toBeGreaterThanOrEqual(AA_NORMAL);
      }
    });
  });
}
