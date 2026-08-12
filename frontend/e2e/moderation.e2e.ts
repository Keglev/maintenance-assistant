import type { Page } from '@playwright/test';

import { E2E_TITLE_PREFIX, expect, signIn, signOut, sweepThrowaways, test } from './support';

/**
 * THE RELEASE DRILL, automated.
 *
 * This is the sequence Carlos runs by hand before every release and the one #39's PR body spells
 * out: a Schichtleiter files a throwaway protocol, an admin corrects it with a reason, archives it
 * with a reason, and the archive still carries who did it and why. It is the insider-threat chain
 * end to end — create, detect, trace, remediate, PRESERVE — and until now nothing but a person
 * clicking could confirm it still worked.
 *
 * CLEANUP IS PART OF THE TEST, NOT A TEARDOWN TRICK. The protocol this test creates is removed the
 * way the application removes protocols: an archive with a mandatory reason, performed by an admin
 * through the UI. Never SQL. Deleting the row directly would be faster and would bypass exactly the
 * audit path this test exists to prove — a cleanup that skips the ledger is a cleanup that would
 * still pass if the ledger were broken.
 *
 * WHAT THAT LEAVES BEHIND, stated because it is a real cost: an archived row and a moderation_event
 * survive every run, by design (ADR-006: no restore, ever). They are capped at 50 per machine with
 * the oldest purged synchronously, so the local database does not grow without bound, and the
 * machine used here is deliberately NOT the E-47 demo machine.
 */

/** Not PR-03: the E-47 demo case lives there and no test should be near it. */
const MACHINE = 'VP-01';

/** A title nobody would type by accident, so an artifact is identifiable and sweepable. */
function throwawayTitle(): string {
  return `${E2E_TITLE_PREFIX} ${Date.now()}`;
}

const BODY = [
  'Symptom: Verpackungslinie stoppt sporadisch nach dem Etikettierer.',
  'Ursache: Lichtschranke verschmutzt.',
  'Massnahme: Lichtschranke gereinigt und Ausrichtung geprueft.',
].join('\n');

const CORRECTED_BODY = [
  'Symptom: Verpackungslinie stoppt sporadisch nach dem Etikettierer.',
  'Ursache: Lichtschranke DEFEKT, nicht nur verschmutzt.',
  'Massnahme: Lichtschranke getauscht, Ausrichtung geprueft.',
].join('\n');

/** Files a protocol as the Schichtleiter — the only role that may write one. */
async function fileProtocol(page: Page, title: string): Promise<void> {
  await signIn(page, 'schichtleiter');
  await page.getByTestId('nav-upload').click();

  // Typed, not uploaded: this is the flow the shift actually uses (#34), and it exercises the
  // client-side File wrapping over the unchanged multipart endpoint.
  await page.getByTestId('mode-text').click();
  await page.getByTestId('upload-machine').selectOption(MACHINE);
  await page.getByTestId('upload-title').fill(title);
  await page.getByTestId('text-input').fill(BODY);
  await page.getByTestId('upload-button').click();

  // 202 Accepted, said out loud by the UI. Waiting on the notice rather than on a timer: the
  // request is answered before the protocol is indexed, and that is the contract.
  await expect(page.getByTestId('accepted')).toBeVisible();
}

/** Finds a protocol by title in the admin's corpus view. */
async function findInCorpus(page: Page, title: string) {
  await page.getByTestId('tab-corpus').click();
  await page.getByTestId('filter-machine').selectOption(MACHINE);
  // The title filter is refused without a machine (400 MACHINE_REQUIRED_FOR_FILTER) and the UI
  // disables it until one is chosen — so the order above is the contract, not a preference.
  await page.getByTestId('filter-title').fill(title);
  await page.getByTestId('filter-apply').click();

  const row = page.getByTestId('moderation-row').filter({ hasText: title });
  await expect(row).toHaveCount(1);
  return row;
}

test.describe('moderation round trip', () => {
  // Runs whether the test passed or failed, so a red run does not poison the next one. The sweep
  // itself now lives in support.ts, because `reindex.e2e.ts` needed exactly the same thing and did
  // not have it — see the note there.
  test.afterEach(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await signIn(page, 'admin');
      await sweepThrowaways(page, MACHINE);
    } finally {
      await context.close();
    }
  });

  test('create, correct with a reason, archive with a reason, and it is still on the record', async ({
    page,
  }) => {
    const title = throwawayTitle();

    // --- 1. The Schichtleiter files it -------------------------------------------------------
    await fileProtocol(page, title);
    await expect(page.getByTestId('uploads-table')).toContainText(title);
    await signOut(page);

    // --- 2. The admin sees it in the corpus --------------------------------------------------
    await signIn(page, 'admin');
    const row = await findInCorpus(page, title);
    await expect(row.getByTestId('row-uploader')).toContainText('schichtleiter');

    // --- 3. Correction, with a mandatory reason ----------------------------------------------
    await row.getByTestId('row-edit').click();
    await expect(page.getByTestId('edit-title')).toHaveValue(title);

    // Machine and type are provenance, not properties: the API refuses a change with 400
    // PROTOCOL_IDENTITY_LOCKED. "Words can be fixed, identity cannot."
    //
    // They are not DISABLED FIELDS — they are not fields at all. The dialog renders them as text,
    // which is a stronger guarantee than a disabled input (nothing to re-enable in a devtools
    // panel) and is what this asserts: the element exists, shows the machine, and is not something
    // that can be typed into.
    await expect(page.getByTestId('edit-machine')).toContainText(MACHINE);
    for (const locked of ['edit-machine', 'edit-type']) {
      const tag = await page.getByTestId(locked).evaluate((node) => node.tagName);
      expect(tag, `${locked} should be static text, not an input`).toBe('P');
      await expect(page.getByTestId(locked).locator('input, select, textarea')).toHaveCount(0);
    }
    await expect(page.getByTestId('edit-locked-hint')).toBeVisible();

    await page.getByTestId('edit-content').fill(CORRECTED_BODY);

    // The reason is required, and the UI enforces it BEFORE the click rather than after: the save
    // button is disabled while the comment is empty, with a hint saying why. Asserted in that shape
    // because it is the stronger one — there is no state in which an unexplained correction can be
    // submitted and then refused.
    await expect(page.getByTestId('edit-reason-required')).toBeVisible();
    await expect(page.getByTestId('edit-save')).toBeDisabled();

    await page.getByTestId('edit-comment').fill('e2e: corrected the root cause');
    await expect(page.getByTestId('edit-save')).toBeEnabled();
    await page.getByTestId('edit-save').click();
    await expect(page.getByTestId('corrected-notice')).toBeVisible();

    // --- 4. The correction is what the corpus now holds ---------------------------------------
    const corrected = await findInCorpus(page, title);
    await corrected.getByTestId('row-open').click();
    // `protocol-body` is the wrapper the viewer always renders — the parsed
    // Symptom/Ursache/Massnahme view AND the raw fallback both live inside it, so this assertion
    // does not depend on which of the two the parser chose for this document.
    await expect(page.getByTestId('protocol-body')).toContainText('DEFEKT');

    // Closed by the button rather than by Escape. Both work, and the button is the one that is
    // unambiguous from a test: Escape depends on where focus is at the moment the key is sent, and
    // the dialog moves focus into itself on a `setTimeout`, so pressing it the instant the content
    // resolves is a race the test would own rather than the application. The Escape path has its
    // own assertion in citation.e2e.ts, where it is the behaviour under test instead of a step.
    await page.getByTestId('protocol-close').click();
    await expect(page.getByTestId('protocol-backdrop')).toHaveCount(0);

    // --- 5. Archived, with a mandatory reason -------------------------------------------------
    const toArchive = await findInCorpus(page, title);
    await toArchive.getByTestId('row-delete').click();
    await expect(page.getByTestId('delete-target')).toContainText(title);

    // Same discipline as the edit, and it matters more here: an archive has no restore, so the
    // reason cannot be an afterthought. The comment travels in the REQUEST BODY rather than as a
    // query parameter, so it never lands in an access log.
    await expect(page.getByTestId('delete-reason-required')).toBeVisible();
    await expect(page.getByTestId('delete-confirm-button')).toBeDisabled();

    await page.getByTestId('delete-comment').fill('e2e: throwaway, removing after the drill');
    await expect(page.getByTestId('delete-confirm-button')).toBeEnabled();
    await page.getByTestId('delete-confirm-button').click();
    await expect(page.getByTestId('removed-notice')).toBeVisible();

    // Gone from the corpus.
    await page.getByTestId('removed-dismiss').click();
    await page.getByTestId('tab-corpus').click();
    await page.getByTestId('filter-machine').selectOption(MACHINE);
    await page.getByTestId('filter-title').fill(title);
    await page.getByTestId('filter-apply').click();
    await expect(page.getByTestId('moderation-row').filter({ hasText: title })).toHaveCount(0);

    // --- 6. And still on the record ------------------------------------------------------------
    // This is the last link of the insider-threat chain and the one #37 was missing: a hard delete
    // removed the bad protocol AND the evidence that it had ever existed.
    await page.getByTestId('tab-archive').click();
    await page.getByTestId('archive-machine').selectOption(MACHINE);

    const archived = page.getByTestId('archive-row').filter({ hasText: title });
    await expect(archived).toHaveCount(1);
    await expect(archived.getByTestId('archive-actor')).toContainText('admin');
    await expect(archived.getByTestId('archive-comment')).toContainText('throwaway');
  });
});
