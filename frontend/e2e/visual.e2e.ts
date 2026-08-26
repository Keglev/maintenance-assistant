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
 * <p><b>A NOTE ON THE TOLERANCE, learned the hard way on 2026-08-14.</b> `maxDiffPixelRatio` is
 * 0.002, and that is ~1,000 pixels on a dialog-sized shot — enough to absorb ONE LINE OF TEXT
 * disappearing. When the approval badge stopped rendering its actor, the duplicate-dialog baselines
 * still passed, and `--update-snapshots` therefore refreshed nothing: it only rewrites what fails.
 * A baseline records what the application LOOKS LIKE, so one that survives a deliberate content
 * change has quietly stopped doing its job. <b>After an intentional change, delete the affected
 * baselines and regenerate rather than trusting `--update-snapshots` to notice.</b> Regeneration is
 * byte-deterministic in the pinned container, so `git status` afterwards is an exact list of what
 * really moved.
 *
 * <p><b>Chosen for churn, not for coverage.</b> Nine surfaces, both palettes. Five of them are
 * places this project has already shipped a visual defect; the sixth (v1.2's unapproved source) is
 * the one case where being seen IS the feature — see {@link UNAPPROVED_ANSWER}; the seventh is the
 * corrector's view of the records table, where what changed is a set of ABSENCES and absence has a
 * layout; the eighth is the duplicate-detection dialog, whose governing rule — inform, never
 * obstruct — is itself a visual claim; the ninth is the viewer's history section, which is where
 * v1.2's provenance went when the records table stopped carrying it. Each has its argument written
 * where it lives. Adding a baseline per view would make a suite nobody dares change.
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
      // The seeded corpus is born approved, so this is the ORDINARY case and the one the two
      // long-standing search baselines should keep recording.
      approved: true,
    },
    {
      label: 'P2',
      protocolId: SEED.secondProtocolId,
      title: 'E-47 nach Programmwechsel auf Teil 4711',
      errorCode: 'E-47',
      incidentDate: '2026-04-02',
      similarity: 0.61,
      approved: true,
    },
  ],
};

/**
 * The same answer with one source nobody has approved.
 *
 * <p>A SIXTH SURFACE, ADDED DELIBERATELY, and the reason is the one this suite was built on: the
 * unapproved marker is the only thing in v1.2 whose whole job is to be SEEN. It introduces a token
 * pair (`--c-review-*`) that appears nowhere else, an amber chip inside a green Mode A block, and a
 * line above the answer text — three things a functional test can assert the existence of and none
 * of which it can notice going pale, colliding or vanishing behind a fade.
 *
 * <p>Scoped to the answer block rather than the page, because that is where all three live: a
 * full-page shot would spend its pixels on chrome the other five baselines already cover.
 */
const UNAPPROVED_ANSWER = {
  ...SHORT_ANSWER,
  citations: [SHORT_ANSWER.citations[0], { ...SHORT_ANSWER.citations[1], approved: false }],
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

    test(`an answer with an unapproved source @visual`, async ({ page }) => {
      // v1.2: the marker on the card, the amber chip inside a green block, and the one-line note
      // above the answer text. See UNAPPROVED_ANSWER for why this earned a baseline of its own.
      await signIn(page, 'techniker');
      await page.route('**/api/query', (route) => route.fulfill({ json: UNAPPROVED_ANSWER }));

      await selectSearchMachine(page, SEED.machineNo);
      await page.getByTestId('question-input').fill('Warum fällt der Druck im Presshub ab?');
      await page.getByTestId('ask-button').click();
      await expect(page.getByTestId('answer-unapproved')).toBeVisible();
      await expect(page.getByTestId('source-card')).toHaveCount(2);

      await expect(page.getByTestId('answer-mode-a')).toHaveScreenshot(
        `search-unapproved-${scheme}.png`,
        { mask: nonDeterministic(page) },
      );
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

    test(`the corrector's view of the same table @visual`, async ({ page }) => {
      /*
       * A SEVENTH SURFACE, and the reasoning both ways, because "chosen for churn, not coverage" is
       * the rule this suite is held to.
       *
       * AGAINST: what defines this view is what is ABSENT — no Löschen, no approval control, no tab
       * strip — and absence is precisely what a functional test asserts well. `role-gating.e2e.ts`
       * checks all four with `toHaveCount(0)`, and the vitest spec checks them again in jsdom.
       *
       * FOR, and it is why this exists: absence has a LAYOUT, and nothing above can see it. The
       * actions cell carries `min-width: 19rem`, sized for the three buttons an admin sees; this
       * role sees two, so the cell's spare width is new geometry no existing baseline covers. The
       * tab strip that disappears was also the element separating the heading from the filter card,
       * and a header row welded to what follows it is exactly the defect (#46) that put this suite
       * in the repository. Two new strings sit above it, either of which can wrap somewhere ugly.
       *
       * Full page for that reason: the missing strip is a whole-page rhythm question, not a table
       * question.
       */
      await signIn(page, 'schichtleiter');
      await page.getByTestId('nav-moderation').click();
      await expect(page.getByTestId('moderation-table')).toBeVisible();

      await expect(page).toHaveScreenshot(`corrections-${scheme}.png`, {
        fullPage: true,
        mask: nonDeterministic(page),
      });
    });

    test(`the duplicate-detection dialog @visual`, async ({ page }) => {
      /*
       * AN EIGHTH SURFACE, and the argument is the same one the unapproved-source baseline won on:
       * being seen a particular way IS the feature.
       *
       * The rule this dialog has to obey is a VISUAL rule — "no red, no warning framing, the review
       * palette, and an approve button that plainly still works". A functional test can assert that
       * the button is enabled and that the class list says `notice-review`; it cannot notice the
       * amber going pale against --c-sunken, a card border vanishing in dark, the percentage
       * colliding with a long title, or the primary button losing its weight beside a list. Every
       * one of those turns "information" back into "obstacle" without changing a single assertion.
       *
       * It also brings TWO new pieces of geometry no baseline covers: cards recessed on --c-sunken
       * inside a dialog surface (the token that inverted between palettes once already), and an
       * approval badge rendered INSIDE a card rather than in a table cell.
       *
       * Scoped to the dialog panel: the page behind it is the Verwaltung table, which already has
       * its own baseline two tests up.
       */
      const CANDIDATE = {
        comparable: true,
        total: 2,
        allIds: ['0f9c5b02-0000-4000-8000-000000000001', '0f9c5b02-0000-4000-8000-000000000003'],
        threshold: 0.92,
        candidates: [
          {
            id: SEED.protocolId,
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
          {
            id: SEED.secondProtocolId,
            title: 'E-47 nach Programmwechsel auf Teil 4711',
            incidentDate: '2024-11-02',
            uploadedBy: 'schichtleiter',
            uploadedAt: '2026-08-07T08:00:00Z',
            similarity: 0.9305,
            // ONE OF EACH, deliberately: the two states are what the reviewer is meant to tell
            // apart, and a baseline showing only one would not hold the distinction.
            approval: { state: 'UNAPPROVED', approvedBy: null, approvedAt: null },
          },
        ],
      };

      await signIn(page, 'admin');
      await page.route('**/similar', (route) => route.fulfill({ json: CANDIDATE }));
      await expect(page.getByTestId('moderation-table')).toBeVisible();

      // The approval queue, so a row with an approve button is on screen whatever the corpus has
      // been doing — the seeded corpus is born approved and the queue is the 15 that are not.
      await page.getByTestId('filter-approval').selectOption('UNAPPROVED');
      await page.getByTestId('row-approve').first().click();
      await expect(page.getByTestId('duplicates-dialog')).toBeVisible();
      // WAIT FOR FOCUS TO LAND, and this line is here because its absence made the dark baseline
      // flaky on its very first run. The dialog moves focus to its first control from a setTimeout,
      // one tick after the panel renders — so a screenshot taken on "visible" catches the panel
      // with or without a focus ring depending on the scheduler. Visible is not settled.
      await expect(page.getByTestId('duplicates-close')).toBeFocused();

      // NO MASKS, and that is a decision rather than an omission: every value in this dialog comes
      // from the stub above, including the dates. Masking them would blank exactly the row of text
      // whose alignment against the percentage is worth recording.
      await expect(page.getByTestId('duplicates-dialog')).toHaveScreenshot(
        `duplicates-${scheme}.png`,
      );
    });

    test(`the protocol viewer with a history @visual`, async ({ page }) => {
      /*
       * A NINTH SURFACE, and the argument is where this PR's design actually lives.
       *
       * Carlos's ruling of 2026-08-14 moved provenance OUT of the records table — its column
       * truncated in production carrying "Freigegeben system:corpus-seed · 12.08.2026" — and INTO
       * this section. So the claim being made is that three ledger entries read well beside a
       * document, and that is a claim about layout: the verb / actor / timestamp line wrapping at a
       * dialog's width, three entries separated by rules without turning the dialog into a table,
       * and the whole thing sitting BELOW the protocol without pushing it off screen.
       *
       * A functional test asserts each string is present; none of them can see the timestamp
       * wrapping under the actor, the separators disappearing in dark, or the section growing until
       * the document it is about is out of view.
       *
       * Three entries and an overflow line, because that is the fullest this section can ever be —
       * the cap is a product decision and the widest case is the one worth recording.
       */
      const HISTORY = {
        limit: 3,
        total: 5,
        events: [
          {
            action: 'UNAPPROVE',
            actor: 'admin',
            comment: 'Massnahme passt nicht zur beschriebenen Ursache.',
            at: '2026-08-14T16:05:00Z',
          },
          {
            action: 'EDIT',
            actor: 'schichtleiter',
            comment: 'Anzugsmoment auf 90 Nm korrigiert.',
            at: '2026-08-14T11:20:00Z',
          },
          {
            action: 'APPROVE',
            actor: 'admin',
            comment: 'approved despite 1 similar protocol(s) on this machine',
            at: '2026-08-13T07:45:00Z',
          },
        ],
      };

      await signIn(page, 'admin');
      await page.route('**/history', (route) => route.fulfill({ json: HISTORY }));
      await expect(page.getByTestId('moderation-table')).toBeVisible();

      await page.getByTestId('row-open').first().click();
      await expect(page.getByTestId('protocol-body')).toBeVisible();
      await expect(page.getByTestId('protocol-history')).toBeVisible();
      // VISIBLE IS NOT SETTLED — the lesson the duplicate dialog's baseline taught on its first run
      // (#56). The dialog moves focus from a setTimeout one tick after the panel renders, so a shot
      // taken on visibility catches it with or without a focus ring depending on the scheduler.
      //
      // THIS DIALOG CANNOT BE WAITED ON THE WAY THAT ONE WAS, and the reason is a documented
      // behaviour rather than a bug: the viewer's first focusable is the Download button, which is
      // DISABLED until the document arrives, so the panel itself takes focus instead (the #50 fix
      // in `dialog.ts`). Which of the two ends up focused therefore depends on whether the fetch
      // beat the timer — a race, and a visible one, because it decides where the ring is drawn.
      // Focus is placed explicitly so the shot is taken from one known state rather than a coin
      // toss. Nothing inside the history section can hold focus, so the ring is outside the crop
      // below — this line makes the RUN deterministic, not the pixels.
      await page.getByTestId('protocol-close').focus();

      // SCOPED TO THE SECTION, AND WITH NO MASKS — both for the same reason. The document above it
      // is whatever protocol happens to be first in the corpus, so a shot of the whole dialog would
      // be mostly a masked rectangle and would clip the third entry below the fold. Every value in
      // the history is stubbed, so scoped this way the baseline is fully deterministic and is
      // entirely about the thing under test.
      await expect(page.getByTestId('protocol-history')).toHaveScreenshot(
        `viewer-history-${scheme}.png`,
      );
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
