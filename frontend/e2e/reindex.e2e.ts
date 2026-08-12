import { E2E_TITLE_PREFIX, expect, signIn, signOut, test } from './support';

/**
 * "The answer changes after a re-index" — step 4 of the drill, and the whole point of #39.
 *
 * An edit that left the old chunks would be WORSE than no edit, because retrieval would keep
 * matching text the document no longer contains. Re-indexing is a condition of the flow, not a step
 * in it, and the only way to see it is to ask a question and watch the answer move.
 *
 * WHY THIS IS A SEPARATE FILE, AND OFF BY DEFAULT. It is the one assertion in this suite that
 * cannot be made without a real LLM: it needs the question embedded and an answer generated, which
 * means a paid call to a shared-capacity provider with a measured 38.9 s worst case (ADR-002).
 * Everything else here runs against an empty `LLM_API_KEY`. Making the whole suite depend on a
 * funded key would mean the suite does not run — and a suite that does not run is worth nothing.
 *
 * Enable with `E2E_LLM=1`, and only against a local stack whose backend has a working key. The
 * budget guards (NFR-7) still apply; this costs a handful of calls per run.
 *
 * NO SLEEPS. Indexing is asynchronous BY DESIGN — the upload answers 202 and a pipeline runs — so
 * the wait is expressed as the condition it actually is: poll the answer until it reflects the
 * correction, or fail. A fixed sleep would be a guess about a machine's speed, and the flake it
 * produces would land on whoever ran the suite next.
 */

const RUN = process.env['E2E_LLM'] === '1';

const MACHINE = 'VP-01';
const QUESTION = 'Warum stoppt die Verpackungslinie nach dem Etikettierer?';

/** A word that appears only in the corrected text, so its presence proves the re-index. */
const CORRECTION_MARKER = 'Zahnriemen';

test.describe('re-index after an edit', () => {
  test.skip(
    !RUN,
    'needs a working LLM_API_KEY on the backend — set E2E_LLM=1 to run it. Everything else in ' +
      'this suite runs without one; see e2e/README.md.',
  );

  test('an edited protocol changes the answer it is cited in', async ({ page }) => {
    const title = `${E2E_TITLE_PREFIX} ${Date.now()}`;

    await signIn(page, 'schichtleiter');
    await page.getByTestId('nav-upload').click();
    await page.getByTestId('mode-text').click();
    await page.getByTestId('upload-machine').selectOption(MACHINE);
    await page.getByTestId('upload-title').fill(title);
    await page
      .getByTestId('text-input')
      .fill(
        'Symptom: Linie stoppt nach dem Etikettierer.\n' +
          'Ursache: Lichtschranke verschmutzt.\n' +
          'Massnahme: Lichtschranke gereinigt.',
      );
    await page.getByTestId('upload-button').click();
    await expect(page.getByTestId('accepted')).toBeVisible();

    // Wait for INDEXED — a condition, polled by the app's own refresh button, not a sleep.
    await expect(async () => {
      await page.getByTestId('refresh-button').click();
      await expect(page.getByTestId('uploads-table')).toContainText('INDEXIERT');
    }).toPass({ timeout: 120_000, intervals: [2_000] });

    await signOut(page);

    // Correct it: a different root cause, carrying a word the original never contained.
    await signIn(page, 'admin');
    await page.getByTestId('filter-machine').selectOption(MACHINE);
    await page.getByTestId('filter-title').fill(title);
    await page.getByTestId('filter-apply').click();
    await page
      .getByTestId('moderation-row')
      .filter({ hasText: title })
      .getByTestId('row-edit')
      .click();
    await page
      .getByTestId('edit-content')
      .fill(
        'Symptom: Linie stoppt nach dem Etikettierer.\n' +
          `Ursache: ${CORRECTION_MARKER} am Etikettierer gerissen.\n` +
          `Massnahme: ${CORRECTION_MARKER} getauscht.`,
      );
    await page.getByTestId('edit-comment').fill('e2e: corrected root cause for the re-index check');
    await page.getByTestId('edit-save').click();
    await expect(page.getByTestId('corrected-notice')).toBeVisible();
    await signOut(page);

    // Ask, and keep asking until the corpus answers with the corrected text. Re-indexing is
    // delete-then-write, so there is a window in which neither version is retrievable.
    await signIn(page, 'techniker');
    await expect(async () => {
      await page.getByTestId('machine-picker').selectOption(MACHINE);
      await page.getByTestId('question-input').fill(QUESTION);
      await page.getByTestId('ask-button').click();
      await expect(page.getByTestId('answer-text')).toContainText(CORRECTION_MARKER, {
        timeout: 45_000,
      });
    }).toPass({ timeout: 240_000, intervals: [5_000] });

    // Cleanup through the app, with a reason — never SQL.
    await signOut(page);
    await signIn(page, 'admin');
    await page.getByTestId('filter-machine').selectOption(MACHINE);
    await page.getByTestId('filter-title').fill(title);
    await page.getByTestId('filter-apply').click();
    await page
      .getByTestId('moderation-row')
      .filter({ hasText: title })
      .getByTestId('row-delete')
      .click();
    await page.getByTestId('delete-comment').fill('e2e: throwaway from the re-index check');
    await page.getByTestId('delete-confirm-button').click();
    await expect(page.getByTestId('removed-notice')).toBeVisible();
  });
});
