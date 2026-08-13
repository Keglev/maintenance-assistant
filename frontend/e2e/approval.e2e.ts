import type { Page } from '@playwright/test';

import {
  E2E_MACHINE,
  E2E_TITLE_PREFIX,
  expect,
  openProtocolList,
  selectSearchMachine,
  signIn,
  signOut,
  sweepThrowaways,
  test,
} from './support';

/**
 * THE TRUST CHAIN, in a browser.
 *
 * <p>v1.2's approval workflow is the most navigation-heavy feature in the project, which is exactly
 * why the e2e foundation was built BEFORE it (PROJECT-PHASES, steps 1 and 2 swapped on 2026-08-11):
 * it is developed against a rendered safety net rather than given one afterwards. This is that net's
 * first new case.
 *
 * <p>What it walks: a protocol arrives unapproved, an administrator approves it and the state says
 * WHO and WHEN, the queue filter finds the unapproved ones without a machine, a Schichtleiter's
 * correction resets the approval, and withdrawing one takes a reason. <b>Every step is a click and
 * every assertion is on what the browser painted.</b> The correction was an authenticated PUT for
 * one release — the Schichtleiter held the permission while the administrator held the screen — and
 * became a click again on 2026-08-14, when the route opened to the role that owns the act.
 *
 * <p><b>THE FOUR-EYES REFUSAL IS NOT REACHABLE FROM HERE, and 2026-08-14 sharpened why: it is not
 * reachable AT ALL, by anybody, and that is the role split working.</b> The rule refuses an approval
 * by whoever filed or last corrected the protocol. Only an admin may approve; only a Techniker or
 * Schichtleiter may file; since 2026-08-13 only a Schichtleiter may correct. An administrator can
 * therefore never be the author and never be the corrector, so no request this API accepts can make
 * either condition true — this is not "no demo account holds two roles", it is "no account can",
 * and adding a second demo administrator would not change it (see the 2026-08-14 note in ADR-006).
 * The check is kept as the belt to the split's braces and is covered by `ProtocolApprovalIT` against
 * a database and by the moderation spec against a stubbed 400. This comment is here so the next
 * reader does not conclude it was forgotten.
 *
 * <p>CLEANUP IS PART OF THE TEST, as everywhere in this suite: what this creates it archives,
 * through the application, with a reason. Never SQL.
 */

/** Not PR-03: the E-47 demo case lives there and no test should be near it. */
const MACHINE = E2E_MACHINE;

function throwawayTitle(): string {
  return `${E2E_TITLE_PREFIX} approval ${Date.now()}`;
}

const BODY = [
  'Symptom: Anlage startet nicht, keine Reaktion beim Einschalten.',
  'Ursache: Not-Halt am Bedienpult war eingerastet.',
  'Massnahme: Not-Halt entriegelt und quittiert, Anlage laeuft.',
].join('\n');

/** Files a throwaway as the Schichtleiter, so the approver is never the author. */
async function fileProtocol(page: Page, title: string): Promise<void> {
  await signIn(page, 'schichtleiter');
  await page.getByTestId('nav-upload').click();
  await page.getByTestId('mode-text').click();
  await page.getByTestId('upload-machine').selectOption(MACHINE);
  await page.getByTestId('upload-title').fill(title);
  await page.getByTestId('text-input').fill(BODY);
  await page.getByTestId('upload-button').click();
  await expect(page.getByTestId('accepted')).toBeVisible();
}

/**
 * Finds a protocol by title in the admin's corpus view, WHATEVER its approval state.
 *
 * <p>The approval filter is cleared first, and that is not tidiness — it is the behaviour under
 * test. It applies on change and it STAYS applied, so after approving from the queue the row this
 * helper is looking for has legitimately left the list it was found in. Leaving the filter set
 * would make every post-approval assertion look for a row the application is correctly no longer
 * showing.
 */
async function findInCorpus(page: Page, title: string) {
  // The Schichtleiter lands on /search, not here — correcting is a job they navigate to.
  await openProtocolList(page);
  // The tab strip exists only for the administrator: a Schichtleiter may not read the archive, and
  // a strip with one tab is a choice that is not a choice. Clicked when present so this helper
  // serves both readers of the same list.
  const corpusTab = page.getByTestId('tab-corpus');
  if (await corpusTab.count()) {
    await corpusTab.click();
  }
  await page.getByTestId('filter-approval').selectOption('');
  await page.getByTestId('filter-machine').selectOption(MACHINE);
  await page.getByTestId('filter-title').fill(title);
  await page.getByTestId('filter-apply').click();

  const row = page.getByTestId('moderation-row').filter({ hasText: title });
  await expect(row).toHaveCount(1);
  return row;
}

test.describe('the approve workflow', () => {
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

  test('an admin approves an unapproved protocol, and the state says who and when', async ({
    page,
  }) => {
    const title = throwawayTitle();

    await fileProtocol(page, title);
    await signOut(page);

    // --- The queue ---------------------------------------------------------------------------
    await signIn(page, 'admin');
    await page.getByTestId('tab-corpus').click();
    await page.getByTestId('filter-approval').selectOption('UNAPPROVED');

    // NO MACHINE CHOSEN, deliberately. The three other filters are refused without one (400
    // MACHINE_REQUIRED_FOR_FILTER) because a title fragment across ten machines answers with rows
    // nobody was looking for. A review queue is the opposite: it is only useful whole, and this is
    // the assertion that the exemption survived into the interface.
    const queued = page.getByTestId('moderation-row').filter({ hasText: title });
    await expect(queued).toHaveCount(1);
    await expect(queued.getByTestId('approval-state')).toHaveAttribute(
      'data-approval',
      'UNAPPROVED',
    );
    // A WORD, not only a colour — the project's accessibility line, asserted on a real render.
    //
    // Asserted as "the label is not empty" rather than as a German string: the interface language
    // is remembered per browser profile, so which of the two dictionaries this run renders is not
    // something this test controls, and pinning one of them made it fail on the sentence rather
    // than on the behaviour. WHICH words appear is the badge spec's business, in jsdom, where the
    // language is set explicitly. What belongs here is that a real render puts a readable label on
    // the screen at all.
    await expect(queued.locator('[data-testid="approval-state"] .approval-label')).not.toBeEmpty();

    // --- The approval ------------------------------------------------------------------------
    // No confirmation dialog: approving affirms the text as it stands, and the reviewer has just
    // read it. Withdrawing is the direction that needs a reason, and it has one below.
    await queued.getByTestId('row-approve').click();
    await expect(page.getByTestId('approval-notice')).toContainText(title);

    const approved = await findInCorpus(page, title);
    await expect(approved.getByTestId('approval-state')).toHaveAttribute(
      'data-approval',
      'APPROVED',
    );
    // AND THE COLUMN SAYS NOTHING ELSE. It carried the approver and the date until Carlos's
    // production drill of #56 found it truncating — "Freigegeben system:corpus-seed · 12.08.2…"
    // running under the action buttons. Who and when are provenance and moved into the viewer.
    await expect(approved.getByTestId('approval-state')).not.toContainText('admin');

    // WHO AND WHEN, ON SCREEN, WHERE THEY LIVE NOW. An approval without an actor is not an audit
    // record — the database constraint says the same thing, and this is that rule reaching a
    // reader, in the record rather than in the list.
    await approved.getByTestId('row-open').click();
    await expect(page.getByTestId('protocol-history')).toBeVisible();
    const entry = page.getByTestId('history-item').first();
    await expect(entry.getByTestId('history-actor')).toContainText('admin');
    // The TIME as well as the day: two acts on one afternoon are a different story from two a
    // month apart, and the table's day-only format cannot tell them apart.
    //
    // ONE OR TWO DIGITS FOR THE HOUR, and that is a correction rather than a loosened assertion.
    // German renders 24-hour ("18:05 Uhr") and English 12-hour ("6:05 PM"), and which dictionary a
    // run gets is a property of the browser profile rather than of this test — the same lesson the
    // approval label above already carries. What belongs here is that a clock time is on screen at
    // all; WHICH format is the date pipe's business, in jsdom, where the language is set.
    await expect(entry.getByTestId('history-when')).toContainText(/\d{1,2}:\d{2}/);
    // Every ledger row carries a reason by database constraint, so an entry never renders bare.
    await expect(entry.getByTestId('history-comment')).not.toBeEmpty();
    await page.getByTestId('protocol-close').click();

    // And it has left the queue, which is what makes the queue a queue.
    await page.getByTestId('filter-approval').selectOption('UNAPPROVED');
    await expect(page.getByTestId('moderation-row').filter({ hasText: title })).toHaveCount(0);
  });

  test('a correction resets the approval, and the interface says so', async ({ page }) => {
    // THE RULE THE WHOLE CHAIN RESTS ON. A correction after approval is text nobody has reviewed,
    // and an approval that survived it would vouch for words the approver never read — which is
    // precisely what ADR-006's original refusal of editing was worried about.
    const title = throwawayTitle();

    await fileProtocol(page, title);
    await signOut(page);

    await signIn(page, 'admin');
    const row = await findInCorpus(page, title);
    await row.getByTestId('row-approve').click();
    await expect(page.getByTestId('approval-notice')).toBeVisible();
    await expect((await findInCorpus(page, title)).getByTestId('approval-state')).toHaveAttribute(
      'data-approval',
      'APPROVED',
    );
    await signOut(page);

    // CLICKED, since 2026-08-14. This was an authenticated PUT while the correction endpoint was
    // the Schichtleiter's and the screen was the administrator's; the route opened and the stand-in
    // helper was deleted.
    await signIn(page, 'schichtleiter');
    const toCorrect = await findInCorpus(page, title);

    // The warning the corrector is owed: this protocol is approved right now, and saving takes that
    // away. It is asserted here rather than in a test of its own because this is the walk where a
    // protocol is actually approved first — the state that makes the sentence appear at all.
    await toCorrect.getByTestId('row-edit').click();
    await expect(page.getByTestId('edit-resets-approval')).toBeVisible();

    await page.getByTestId('edit-content').fill(BODY.replace('entriegelt', 'GETAUSCHT, war defekt'));
    await page.getByTestId('edit-comment').fill('e2e: Ursache war ein Defekt, nicht die Bedienung');
    await page.getByTestId('edit-save').click();
    await expect(page.getByTestId('corrected-notice')).toBeVisible();
    await signOut(page);

    await signIn(page, 'admin');
    const reset = await findInCorpus(page, title);
    await expect(reset.getByTestId('approval-state')).toHaveAttribute(
      'data-approval',
      'UNAPPROVED',
    );
    // Back in the queue, which is where a protocol whose text has changed belongs.
    await page.getByTestId('filter-approval').selectOption('UNAPPROVED');
    await expect(page.getByTestId('moderation-row').filter({ hasText: title })).toHaveCount(1);
  });

  test('similar protocols are shown before an approval, and the approval still goes through', async ({
    page,
  }) => {
    // DUPLICATE DETECTION, WHERE IT ACTUALLY MATTERS: an approver looking at the corpus's own
    // opinion of what they are about to vouch for. Two protocols, one body — which is what a real
    // duplicate is, two people writing up the same fault because neither knew the other had.
    //
    // NEEDS AN EMBEDDING, so it runs only with E2E_LLM set, like the re-index test: a protocol with
    // no vectors has nothing to compare and the check correctly reports "not comparable". Free
    // against the provider stub — see e2e/README.md.
    test.skip(!process.env.E2E_LLM, 'needs indexed vectors; set E2E_LLM=1 (the stub is free)');

    const first = `${throwawayTitle()} A`;
    const second = `${throwawayTitle()} B`;

    await fileProtocol(page, first);
    await page.getByTestId('mode-text').click();
    await page.getByTestId('upload-machine').selectOption(MACHINE);
    await page.getByTestId('upload-title').fill(second);
    await page.getByTestId('text-input').fill(BODY);
    await page.getByTestId('upload-button').click();
    await expect(page.getByTestId('accepted')).toBeVisible();

    // Indexed is the precondition, not the subject: the comparison runs on vectors, and vectors
    // arrive asynchronously. Polled through the application's own refresh, never slept on.
    const firstRow = page.getByTestId('uploads-table').locator('tr').filter({ hasText: first });
    const secondRow = page.getByTestId('uploads-table').locator('tr').filter({ hasText: second });
    await expect(async () => {
      await page.getByTestId('refresh-button').click();
      await expect(firstRow.locator('.status-indexed')).toHaveCount(1, { timeout: 2_000 });
      await expect(secondRow.locator('.status-indexed')).toHaveCount(1, { timeout: 2_000 });
    }).toPass({ timeout: 180_000, intervals: [3_000] });
    await signOut(page);

    await signIn(page, 'admin');
    const toApprove = await findInCorpus(page, second);
    await toApprove.getByTestId('row-approve').click();

    // --- What the approver is told -----------------------------------------------------------
    await expect(page.getByTestId('duplicates-dialog')).toBeVisible();
    await expect(page.getByTestId('duplicates-target')).toContainText(second);
    const card = page.getByTestId('duplicate-card').filter({ hasText: first });
    await expect(card).toHaveCount(1);
    // Its OWN approval state, which is the field the decision turns on: this one is unapproved, so
    // the situation is "a second account of one fault", not "a copy of something already vouched
    // for". Asserted on the badge's data attribute rather than on a word, because the interface
    // language is a property of the browser profile and not of this test.
    await expect(card.getByTestId('approval-state')).toHaveAttribute('data-approval', 'UNAPPROVED');
    await expect(card.getByTestId('duplicate-score')).toContainText('%');

    // --- It is INFORMATION, and the button says so -------------------------------------------
    // The whole feature in one assertion. Nothing here is disabled, nothing is red, and the
    // approval below completes: the E-47 four are the reason a threshold must never refuse.
    await expect(page.getByTestId('duplicates-approve')).toBeEnabled();

    // --- And the evidence is one click away ---------------------------------------------------
    // A percentage is not a comparison. The candidate opens in the same read-only viewer the rest
    // of the screen uses, and the list is still there afterwards rather than making the reviewer
    // start again.
    await card.getByTestId('duplicate-open').click();
    await expect(page.getByTestId('protocol-body')).toBeVisible();
    await page.getByTestId('protocol-close').click();
    await expect(page.getByTestId('duplicates-dialog')).toBeVisible();

    await page.getByTestId('duplicates-approve').click();
    await expect(page.getByTestId('approval-notice')).toContainText(second);

    const approved = await findInCorpus(page, second);
    await expect(approved.getByTestId('approval-state')).toHaveAttribute(
      'data-approval',
      'APPROVED',
    );
  });

  test('withdrawing an approval takes a reason before it takes effect', async ({ page }) => {
    const title = throwawayTitle();

    await fileProtocol(page, title);
    await signOut(page);

    await signIn(page, 'admin');
    const row = await findInCorpus(page, title);
    await row.getByTestId('row-approve').click();
    await expect(page.getByTestId('approval-notice')).toBeVisible();

    const approved = await findInCorpus(page, title);
    await approved.getByTestId('row-withdraw').click();
    await expect(page.getByTestId('withdraw-target')).toContainText(title);

    // Enforced BEFORE the click rather than by a 400 afterwards, the same shape the edit and delete
    // dialogs use: there is no state in which an unexplained withdrawal can be submitted.
    await expect(page.getByTestId('withdraw-reason-required')).toBeVisible();
    await expect(page.getByTestId('withdraw-confirm-button')).toBeDisabled();

    await page.getByTestId('withdraw-comment').fill('e2e: Massnahme passt nicht zur Ursache');
    await expect(page.getByTestId('withdraw-confirm-button')).toBeEnabled();
    await page.getByTestId('withdraw-confirm-button').click();
    await expect(page.getByTestId('approval-notice')).toBeVisible();

    const withdrawn = await findInCorpus(page, title);
    await expect(withdrawn.getByTestId('approval-state')).toHaveAttribute(
      'data-approval',
      'UNAPPROVED',
    );
    // Still in the corpus and still searchable — withdrawing trust is not removing a protocol, and
    // a reviewer who assumed otherwise would use the control differently.
    await expect(withdrawn.getByTestId('row-open')).toBeVisible();
  });
});

test.describe('the approved-only facet', () => {
  /**
   * The query is intercepted rather than answered for real, the same trade `citation.e2e.ts` makes
   * and for the same reason: a real answer is a paid call to a shared-capacity provider with a
   * measured 38.9 s worst case and non-deterministic prose. What is under test here is what the
   * BROWSER SENT — which is real, and is the thing a checkbox can get wrong.
   */
  const ANSWER = {
    mode: 'B',
    language: 'de',
    answer: 'Dosierpumpen pruefen.\nFuellstandssensoren pruefen.',
    claims: [],
    citations: [],
  };

  test('sends the whole corpus by default, and narrows it only when asked', async ({ page }) => {
    const sent: (boolean | undefined)[] = [];
    await page.route('**/api/query', async (route) => {
      sent.push((route.request().postDataJSON() as { approvedOnly?: boolean }).approvedOnly);
      await route.fulfill({ json: ANSWER });
    });

    await signIn(page, 'techniker');
    await selectSearchMachine(page, 'PR-03');
    await page.getByTestId('question-input').fill('Warum faellt der Druck im Presshub ab?');

    // OFF by default is the DECISION of 2026-08-11, not a convenience — the admin may not review at
    // a weekend and the factory does not stop.
    await expect(page.getByTestId('approved-only')).not.toBeChecked();
    await page.getByTestId('ask-button').click();
    await expect(page.getByTestId('answer-mode-b')).toBeVisible();

    // No note: this answer was asked against everything, so Mode B means what it has always meant.
    await expect(page.getByTestId('approved-only-note')).toHaveCount(0);

    await page.getByTestId('approved-only').check();
    await page.getByTestId('ask-button').click();
    await expect(page.getByTestId('approved-only-note')).toBeVisible();

    expect(sent, 'the facet has to reach the request, not just the checkbox').toEqual([false, true]);

    // THE EMPTY STATE OF THE FACET, and the reason it needs one: a narrowed search that finds
    // nothing answers Mode B exactly as a genuine gap in the corpus does. Without the way back, a
    // reader concludes the plant has no protocol on this fault.
    await page.getByTestId('approved-only-widen').click();
    await expect(page.getByTestId('approved-only-note')).toHaveCount(0);
    expect(sent).toEqual([false, true, false]);
  });
});
