import type { Page } from '@playwright/test';

import {
  E2E_MACHINE,
  E2E_TITLE_PREFIX,
  correctAsSchichtleiter,
  expect,
  signIn,
  signOut,
  sweepThrowaways,
  test,
} from './support';

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
const MACHINE = E2E_MACHINE;

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

    // --- 2. The admin sees it in the corpus, unapproved ---------------------------------------
    await signIn(page, 'admin');
    let row = await findInCorpus(page, title);
    await expect(row.getByTestId('row-uploader')).toContainText('schichtleiter');

    // A newly filed protocol is UNAPPROVED and searchable, which is the whole of decision 1: the
    // admin may not review at a weekend and the factory does not stop.
    await expect(row.getByTestId('approval-state')).toHaveAttribute('data-approval', 'UNAPPROVED');

    // The administrator has no correction button since 2026-08-13. Asserted here rather than in a
    // test of its own, because "the admin cannot correct" and "the admin approves" are one decision
    // and this is the drill that walks through it.
    await expect(row.getByTestId('row-edit')).toHaveCount(0);

    // --- 3. Correction, with a mandatory reason ----------------------------------------------
    //
    // PERFORMED AS THE SCHICHTLEITER, AND NOT BY CLICKING, and both halves of that need saying.
    //
    // Since 2026-08-13 correcting is the Schichtleiter's and the administrator has no Bearbeiten
    // button — the endpoint answers 403 for them. But `/moderation` is guarded by
    // `roleGuard('admin')`, so the role that OWNS this act cannot reach the screen it lives on:
    // the correction path currently has no interface for anybody. That is a routing decision for
    // Carlos, reported rather than fixed here, and it is why this step is an authenticated PUT
    // instead of the clicks it used to be.
    //
    // It is still the real backend, the real token and the real role. What it is not is a click,
    // and this suite's own rule says an API call is not one — so it is ARRANGEMENT, and every
    // assertion around it stays in the browser. The day the route opens, this helper is deleted
    // and the clicks come back.
    await signOut(page);
    await signIn(page, 'schichtleiter');

    // The identity lock is exercised on the way past, because it is the one rule of the correction
    // that cannot be checked from a screen nobody can open: machine and type are provenance, not
    // properties, and an attempt to change them is refused 400 PROTOCOL_IDENTITY_LOCKED rather than
    // silently ignored. "Words can be fixed, identity cannot."
    const refused = await correctAsSchichtleiter(page, title, CORRECTED_BODY, { machineNo: 'PR-03' });
    expect(refused.status, 'moving a protocol to another machine must be refused').toBe(400);
    expect(refused.reason).toBe('PROTOCOL_IDENTITY_LOCKED');

    const accepted = await correctAsSchichtleiter(page, title, CORRECTED_BODY);
    expect(accepted.status, 'the Schichtleiter is the corrector in the chain').toBe(202);

    await signOut(page);
    await signIn(page, 'admin');
    row = await findInCorpus(page, title);

    // --- 4. The correction is what the corpus now holds ---------------------------------------
    await row.getByTestId('row-open').click();
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
