import { HttpTestingController } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  Roles,
  click,
  find,
  get,
  page,
  protocol,
  renderModeration,
  setUpModeration,
  tearDownModeration,
  type,
} from './moderation.fixtures';

/**
 * What the moderation screen says when a call fails.
 *
 * <p>The contract under test: a refused or broken request leaves the screen USABLE and SAYING SO.
 * Never a spinner that never stops, never a dialog frozen mid-action, and never a silent return to
 * a list that looks exactly as it did — which a reviewer would read as "it worked".
 *
 * <p>These are the error callbacks of the four calls this view makes on its own initiative. They
 * were the largest uncovered block in the component: the happy paths all had tests, so the code that
 * runs on the worst day was the code with no test at all.
 *
 * <p>OUT OF SCOPE: the failures moderation.spec.ts already covers — a refused approval, a refused
 * identity change, an already-archived protocol, an unreadable machine list.
 *
 * <p>SIBLINGS: moderation.spec.ts, moderation.interaction.spec.ts, moderation.dialogs.spec.ts, all
 * arranged by moderation.fixtures.ts.
 */
describe('Moderation — when a call fails', () => {
  let http: HttpTestingController;
  let roles: Roles;

  beforeEach(async () => {
    ({ http, roles } = await setUpModeration());
  });

  afterEach(() => tearDownModeration(http));

  it('stops loading and says so when the corpus cannot be read', async () => {
    const rendered = await renderModeration(http, page([protocol()], 10));

    await click(rendered, 'page-next');
    http
      .expectOne((request) => request.url === '/api/moderation/protocols')
      .flush('nope', { status: 500, statusText: 'Server Error' });
    await rendered.whenStable();

    // The loading line is gone. A failed load that left `loading` set would leave the screen saying
    // "wird geladen" for as long as the reviewer is willing to wait for something that already
    // finished — the one state from which nothing on the page tells the truth.
    expect(find(rendered, 'moderation-loading')).toBeNull();
    expect(get(rendered, 'moderation-failure').textContent?.trim()).not.toBe('');
  });

  it('reports an unreadable archive without leaving it spinning', async () => {
    const rendered = await renderModeration(http);

    await click(rendered, 'tab-archive');
    http
      .expectOne((request) => request.url === '/api/moderation/protocols/deleted')
      .flush('nope', { status: 503, statusText: 'Service Unavailable' });
    await rendered.whenStable();

    expect(find(rendered, 'archive-loading')).toBeNull();
    expect(get(rendered, 'moderation-failure').textContent?.trim()).not.toBe('');
    // Still on the archive tab, and it is empty rather than showing the corpus: a failed read must
    // not fall back to the live list, which is the one thing on this screen the archive is not.
    expect(find(rendered, 'archive-table')).toBeNull();
    expect(find(rendered, 'moderation-table')).toBeNull();
  });

  it('says the stored text could not be read instead of offering an empty box to correct', async () => {
    const rendered = await renderModeration(http);
    roles.set(['schichtleiter']);
    await rendered.whenStable();

    await click(rendered, 'row-edit');
    // .error() rather than .flush(): the call asks for a Blob, and the testing backend cannot
    // convert a string body to one — the failure would be the harness's, not the component's.
    http
      .expectOne('/api/moderation/protocols/p-1/document')
      .error(new ProgressEvent('error'), { status: 500, statusText: 'Server Error' });
    await rendered.whenStable();

    // THE DIALOG STAYS OPEN AND SAYS WHY. The dangerous shape here is the quiet one: the document
    // fetch fails, the textarea stays empty, and a correction saved from it replaces the protocol
    // with nothing. The reader has to be told that the box is empty because the read failed.
    expect(find(rendered, 'edit-loading')).toBeNull();
    expect(get(rendered, 'edit-failure').textContent?.trim()).not.toBe('');
    expect((get(rendered, 'edit-content') as HTMLTextAreaElement).value).toBe('');
  });

  it('closes the withdrawal dialog on a refusal and reports it beside the row', async () => {
    const rendered = await renderModeration(http);

    await click(rendered, 'row-withdraw');
    await type(rendered, 'withdraw-comment', 'Massnahme passt nicht zur Ursache');
    await click(rendered, 'withdraw-confirm-button');
    http
      .expectOne('/api/moderation/protocols/p-1/approval')
      .flush('nope', { status: 409, statusText: 'Conflict' });
    await rendered.whenStable();

    // The dialog goes, and the refusal is reported ON THE ROW rather than inside a dialog that is
    // no longer there. The list is NOT reloaded — nothing changed, and a reload would redraw the row
    // as if the attempt had never been made.
    expect(find(rendered, 'withdraw-target')).toBeNull();
    expect(get(rendered, 'approval-failure').textContent?.trim()).not.toBe('');
    expect(get(rendered, 'moderation-table').textContent).toContain('E-47 Druckabfall');
  });

  it('keeps the typed reason for a retry of the SAME protocol, so a blip costs nothing', async () => {
    const rendered = await renderModeration(http);

    await click(rendered, 'row-withdraw');
    await type(rendered, 'withdraw-comment', 'erster Versuch');
    await click(rendered, 'withdraw-confirm-button');
    http
      .expectOne('/api/moderation/protocols/p-1/approval')
      .flush('nope', { status: 500, statusText: 'Server Error' });
    await rendered.whenStable();

    await click(rendered, 'row-withdraw');

    // TWO THINGS, and both are about the reviewer's next thirty seconds. The reason survives for
    // THIS protocol, so reopening its dialog does not mean writing the sentence again — and
    // `approving` is cleared on the error path, so the button works. Had it not been, one failed
    // request would have disabled every approval control on the screen until a reload.
    //
    // The reason is scoped to the protocol it was written about; the test below is the other half.
    expect((get(rendered, 'withdraw-comment') as HTMLTextAreaElement).value).toBe('erster Versuch');
    expect((get(rendered, 'withdraw-confirm-button') as HTMLButtonElement).disabled).toBe(false);

    await click(rendered, 'withdraw-cancel');
  });

  it('starts blank for a DIFFERENT protocol after a failed withdrawal', async () => {
    const rendered = await renderModeration(
      http,
      page([protocol({ id: 'p-1', title: 'Erstes Protokoll' }), protocol({ id: 'p-2', title: 'Zweites Protokoll' })], 2),
    );

    await withdrawFromRow(rendered, 0);
    await type(rendered, 'withdraw-comment', 'Massnahme passt nicht zur Ursache');
    await click(rendered, 'withdraw-confirm-button');
    http
      .expectOne('/api/moderation/protocols/p-1/approval')
      .flush('nope', { status: 500, statusText: 'Server Error' });
    await rendered.whenStable();

    await withdrawFromRow(rendered, 1);

    // THE LEDGER IS WHY. A withdrawal reason is written into the moderation ledger and attributed as
    // though it were meant about that protocol — permanently, since the archive has no restore. A
    // sentence left over from the protocol above it would be one click from becoming a false record
    // about this one, written by someone who never said it.
    expect((get(rendered, 'withdraw-target') as HTMLElement).textContent).toContain(
      'Zweites Protokoll',
    );
    expect((get(rendered, 'withdraw-comment') as HTMLTextAreaElement).value).toBe('');
    // Blank means refused, not merely empty: the dialog asks for a reason before it will send.
    expect(find(rendered, 'withdraw-reason-required')).not.toBeNull();

    await click(rendered, 'withdraw-cancel');
  });

  /** Opens the withdrawal dialog from one particular row — the rows carry identical test ids. */
  async function withdrawFromRow(
    rendered: Awaited<ReturnType<typeof renderModeration>>,
    index: number,
  ): Promise<void> {
    const rows = (rendered.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="moderation-row"]',
    );
    (rows[index].querySelector('[data-testid="row-withdraw"]') as HTMLButtonElement).click();
    await rendered.whenStable();
  }
});
