import type { Locator, Page } from '@playwright/test';

import { KEYCLOAK_URL } from './guard';
import { expect, selectSearchMachine, signIn, test } from './support';

/**
 * VISUAL REGRESSION — the surfaces that actually broke.
 *
 * <p>THE MOTIVATION IS MEASURED, NOT THEORETICAL. v1.1 spent FOUR pull requests (#41, #44, #45, #46)
 * on spacing and layout defects, and every one of them was found the same way: Carlos opened
 * production and looked. #45's source list lost its top margin only when a toggle appeared between
 * two elements; #46's header row sat welded to the list under it. Nothing in 226 unit tests or 21
 * functional e2e tests could see any of it, because all of them assert what the DOM SAYS and none of
 * them assert what the page LOOKS LIKE.
 *
 * <p><b>What this buys.</b> A pixel diff on a pull request, before production, instead of a person
 * discovering it afterwards.
 *
 * <p><b>What it does not buy, and this belongs at the top rather than in a footnote.</b> A baseline
 * records what the application looks like — NOT what it should look like. A screenshot of an ugly
 * layout is a perfectly valid baseline, and it will happily hold that ugliness in place forever.
 * These tests prove that pixels CHANGED; a human still decides whether they changed for the better.
 * That is why an intentional design change updates its baselines in the SAME pull request that
 * causes it (see e2e/README.md), and why a "fix the baselines" commit afterwards is the one
 * workflow this suite must never acquire.
 *
 * <p><b>Chosen for churn, not for coverage.</b> Five surfaces, both palettes. Every one of them is a
 * place this project has already shipped a visual defect. Adding a baseline per view would make a
 * suite nobody dares change.
 */

/** The seeded E-47 demo case — the same fixture citation.e2e.ts uses. */
const SEED = {
  machineNo: 'PR-03',
  protocolId: '0f9c5b02-0000-4000-8000-000000000001',
  secondProtocolId: '0f9c5b02-0000-4000-8000-000000000002',
};

/**
 * A canned Mode A answer.
 *
 * <p>STUBBED FOR THE SAME REASON `citation.e2e.ts` stubs, and here it is not an optimisation but a
 * precondition: a language model's prose differs between runs, so a baseline of a live answer would
 * fail on its second run and be worthless on its first. The stub fixes the words; everything about
 * how they are LAID OUT — which is the thing under test — is the real application.
 */
const SHORT_ANSWER = {
  mode: 'A',
  language: 'de',
  answer:
    'Der Druckabfall im Presshub entsteht meist am Druckbegrenzungsventil [P1]. ' +
    'Nach einem Programmwechsel ist zusätzlich der Parametersatz zu prüfen [P2].',
  claims: [
    { text: 'Druckbegrenzungsventil prüfen', label: 'P1' },
    { text: 'Parametersatz nach Programmwechsel prüfen', label: 'P2' },
  ],
  citations: [
    {
      label: 'P1',
      protocolId: SEED.protocolId,
      title: 'E-47 Druckabfall im Presshub',
      errorCode: 'E-47',
      incidentDate: '2026-03-14',
      similarity: 0.69,
    },
    {
      label: 'P2',
      protocolId: SEED.secondProtocolId,
      title: 'E-47 nach Programmwechsel auf Teil 4711',
      errorCode: 'E-47',
      incidentDate: '2026-04-02',
      similarity: 0.61,
    },
  ],
};

/**
 * The same answer, made long enough to trip the clamp.
 *
 * <p>The drawer opens above 50vh, so this has to be genuinely tall rather than nominally long — the
 * component measures the rendered height, which is the whole reason the clamp is honest.
 */
const LONG_ANSWER = {
  ...SHORT_ANSWER,
  answer:
    Array.from(
      { length: 14 },
      (_, index) =>
        `Schritt ${index + 1}: Der Druckabfall im Presshub wurde am Druckbegrenzungsventil ` +
        `festgestellt und der Parametersatz nach dem Programmwechsel geprueft [P1].`,
    ).join(' ') + ' Abschliessend ist der Parametersatz zu dokumentieren [P2].',
};

/**
 * Everything that is allowed to differ between two correct runs.
 *
 * <p>Each entry is a decision, and an unmasked one of these is a baseline that fails tomorrow
 * morning for no reason at all. Every mask is listed in the PR body with its reason.
 */
function nonDeterministic(page: Page): Locator[] {
  return [
    // A clock. The corpus is seeded at container start, so "uploaded at" is the time the CI runner
    // happened to boot — different on every single run.
    page.locator('.num'),
    page.getByTestId('archive-row').locator('td'),
    // The health dot polls a live backend and is green, amber or grey depending on when the shot
    // was taken relative to its 60 s interval.
    page.getByTestId('health-dot'),
    // Similarity is a float from a vector search. It is stable for a fixed corpus and a fixed model,
    // and this suite deliberately does not depend on that holding: a reranker or a re-embed would
    // move it, and that is a retrieval change, not a layout regression.
    page.locator('.source-meta'),
  ];
}

/**
 * The one viewport, and why this one.
 *
 * <p>1280×900 is the width the two-column layouts are actually about: `.search-layout` goes
 * two-column at 64rem (1024px) and `.page-wide` at 1600px, so 1280 exercises the split search view
 * and the standard container without being the extreme that #44 was about. It is also close to the
 * commonest desktop viewport a recruiter opening the demo will have.
 *
 * <p>ONE viewport on purpose. Every extra one multiplies the baselines to review and the ways a run
 * can go red; the tablet and 1920px cases are recorded as future options in ADR-007 rather than
 * taken now.
 */
const VIEWPORT = { width: 1280, height: 900 };

const SCHEMES = ['light', 'dark'] as const;

for (const scheme of SCHEMES) {
  test.describe(`visual — ${scheme}`, () => {
    test.use({ colorScheme: scheme, viewport: VIEWPORT });

    test(`the login page @visual`, async ({ page }) => {
      // #42 and #47: the theme itself, the realm heading that shipped at 1.09:1, the back-link that
      // sat glued to the card edge, and the demo box that was full-bleed.
      await page.goto('/');
      await page.getByTestId('sign-in').click();
      await page.waitForURL(`${KEYCLOAK_URL}/**`);
      await expect(page.locator('.wa-demo')).toBeVisible();

      await expect(page).toHaveScreenshot(`login-${scheme}.png`, { fullPage: true });
    });

    test(`a Mode A answer @visual`, async ({ page }) => {
      await signIn(page, 'techniker');
      await page.route('**/api/query', (route) => route.fulfill({ json: SHORT_ANSWER }));

      await selectSearchMachine(page, SEED.machineNo);
      await page.getByTestId('question-input').fill('Warum fällt der Druck im Presshub ab?');
      await page.getByTestId('ask-button').click();
      await expect(page.getByTestId('answer-mode-a')).toBeVisible();
      await expect(page.getByTestId('source-card')).toHaveCount(2);

      await expect(page).toHaveScreenshot(`search-mode-a-${scheme}.png`, {
        fullPage: true,
        mask: nonDeterministic(page),
      });
    });

    test(`a clamped long answer @visual`, async ({ page }) => {
      // THE SURFACE THAT PRODUCED THREE DEFECTS IN A ROW: the answer body, the drawer toggle
      // between it and the sources (#45 T1), the sources header row (#46 T1) and the first card.
      await signIn(page, 'techniker');
      await page.route('**/api/query', (route) => route.fulfill({ json: LONG_ANSWER }));

      await selectSearchMachine(page, SEED.machineNo);
      await page.getByTestId('question-input').fill('Warum fällt der Druck im Presshub ab?');
      await page.getByTestId('ask-button').click();

      // Clamped, not merely long: the toggle only exists once the component has MEASURED an
      // overflow, and a screenshot taken before that is of a different layout.
      await expect(page.getByTestId('answer-toggle')).toBeVisible();
      await expect(page.locator('.answer-body.clamped')).toBeVisible();

      await expect(page.getByTestId('answer-mode-a')).toHaveScreenshot(
        `search-clamped-${scheme}.png`,
        { mask: nonDeterministic(page) },
      );
    });

    test(`the Verwaltung records table @visual`, async ({ page }) => {
      // #41 T1/T4: the full-width table and its row actions, which overflowed at 1920px.
      await signIn(page, 'admin');
      await expect(page.getByTestId('moderation-table')).toBeVisible();

      await expect(page).toHaveScreenshot(`moderation-${scheme}.png`, {
        fullPage: true,
        mask: nonDeterministic(page),
      });
    });

    test(`the upload view in text mode @visual`, async ({ page }) => {
      // #41 T3 and #45 T2: the two-column layout, and the top alignment that was fixed twice.
      await signIn(page, 'schichtleiter');
      await page.getByTestId('nav-upload').click();
      await page.getByTestId('mode-text').click();
      await expect(page.getByTestId('text-input')).toBeVisible();

      // The uploads table below carries timestamps and grows with every run of the moderation
      // suite, so the baseline is the FORM — which is the part that has a layout history.
      await expect(page.locator('.upload-form')).toHaveScreenshot(`upload-text-${scheme}.png`);
    });
  });
}
