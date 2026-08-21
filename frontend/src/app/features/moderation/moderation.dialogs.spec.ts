import { HttpTestingController } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { SimilarityReport } from '../../core/api/api.types';
import {
  Roles,
  click,
  find,
  get,
  noSimilar,
  openArchive,
  openEdit,
  openViewer,
  page,
  protocol,
  renderModeration,
  setUpModeration,
  tearDownModeration,
  type,
} from './moderation.fixtures';

/**
 * Closing, cancelling and deciding inside the moderation dialogs.
 *
 * <p>The contract under test: every dialog on this screen can be ABANDONED, and abandoning one
 * leaves nothing behind — no half-typed reason waiting to attach itself to the next protocol, no
 * request sent. Each dialog has two ways out (its close control and its cancel button) and both must
 * mean the same thing, which is exactly the kind of pair that rots when only one of them is driven.
 *
 * <p>Also here: the two decisions offered inside the viewer, where the evidence is.
 *
 * <p>OUT OF SCOPE: what the dialogs do when they are CONFIRMED — moderation.spec.ts covers
 * deleting, correcting, approving and withdrawing — and the failure paths, which are
 * moderation.failures.spec.ts.
 *
 * <p>SIBLINGS: moderation.spec.ts, moderation.interaction.spec.ts, moderation.failures.spec.ts, all
 * arranged by moderation.fixtures.ts.
 */
describe('Moderation — leaving a dialog', () => {
  let http: HttpTestingController;
  let roles: Roles;

  beforeEach(async () => {
    ({ http, roles } = await setUpModeration());
  });

  afterEach(() => tearDownModeration(http));

  const unapproved = () =>
    page([protocol({ approval: { state: 'UNAPPROVED', approvedBy: null, approvedAt: null } })]);

  it('closes the viewer without touching the protocol it was showing', async () => {
    const fixture = await renderModeration(http);
    await openViewer(fixture, http);

    await click(fixture, 'protocol-close');

    // The viewer is read-only, so closing it is the whole interaction — and the row it was opened
    // from is still there, which is the part a reader would notice if `viewing` were left set.
    expect(find(fixture, 'protocol-dialog')).toBeNull();
    expect(get(fixture, 'moderation-table').textContent).toContain('E-47 Druckabfall');
  });

  it('closes the archived viewer and stays in the archive', async () => {
    const fixture = await renderModeration(http);
    await openArchive(fixture, http);

    await openViewer(fixture, http, { testId: 'archive-open', id: 'a-1', source: 'archive' });

    await click(fixture, 'protocol-close');

    // Closing the viewer must not also leave the archive: the reader came here to review several
    // removals, and being returned to the corpus after each one would make that ten clicks.
    expect(find(fixture, 'protocol-dialog')).toBeNull();
    expect(find(fixture, 'archive-table')).not.toBeNull();
  });

  it('closing the delete confirmation deletes nothing', async () => {
    const fixture = await renderModeration(http);

    await click(fixture, 'row-delete');
    await type(fixture, 'delete-comment', 'aus Versehen geöffnet');

    await click(fixture, 'delete-confirm-close');

    // No DELETE anywhere: the teardown's verify() is what enforces that, and it is the assertion
    // that matters. Closing a confirmation is a refusal, and a dialog whose close button confirmed
    // would be the worst possible reading of the control.
    expect(find(fixture, 'delete-target')).toBeNull();
    expect(get(fixture, 'moderation-table').textContent).toContain('E-47 Druckabfall');
  });

  it('closing the withdrawal dialog forgets the reason that was typed into it', async () => {
    const fixture = await renderModeration(http);

    await click(fixture, 'row-withdraw');
    await type(fixture, 'withdraw-comment', 'Massnahme passt nicht zur Ursache');

    await click(fixture, 'withdraw-confirm-close');
    await click(fixture, 'row-withdraw');

    // Reopened empty. A reason left in the box would be attached to whatever is withdrawn next —
    // the ledger would then carry a sentence about a different protocol, written by someone who
    // never meant it about this one.
    expect((get(fixture, 'withdraw-comment') as HTMLTextAreaElement).value).toBe('');
    expect(find(fixture, 'withdraw-reason-required')).not.toBeNull();
  });

  it('cancelling the withdrawal dialog does the same as closing it', async () => {
    const fixture = await renderModeration(http);

    await click(fixture, 'row-withdraw');
    await type(fixture, 'withdraw-comment', 'noch einmal ansehen');
    await click(fixture, 'withdraw-cancel');

    expect(find(fixture, 'withdraw-target')).toBeNull();

    await click(fixture, 'row-withdraw');
    // Two ways out of one dialog, and they have to mean the same thing: a cancel button that left
    // the comment behind while the close control cleared it is a difference nobody would look for.
    expect((get(fixture, 'withdraw-comment') as HTMLTextAreaElement).value).toBe('');
  });

  it('closing the correction dialog abandons the correction', async () => {
    const fixture = await renderModeration(http);
    await openEdit(fixture, http, roles);
    await type(fixture, 'edit-comment', 'Drehmoment war falsch');

    await click(fixture, 'edit-close');

    // Nothing sent, and the dialog is gone. The document was already fetched when it opened, so the
    // only thing at stake in closing it is that no PUT follows — again enforced by verify().
    expect(find(fixture, 'edit-content')).toBeNull();
    expect(get(fixture, 'moderation-table').textContent).toContain('E-47 Druckabfall');
  });

  it('cancelling the correction dialog reopens it on the stored text, not on the abandoned draft', async () => {
    const fixture = await renderModeration(http);
    await openEdit(fixture, http, roles);
    await type(fixture, 'edit-content', 'halb getippt und verworfen');
    await click(fixture, 'edit-cancel');

    await click(fixture, 'row-edit');
    http
      .expectOne('/api/moderation/protocols/p-1/document')
      .flush(new Blob(['Anzugsmoment 90 Nm'], { type: 'text/plain' }));
    await fixture.whenStable();
    await fixture.whenStable();

    // Refetched. An abandoned draft that survived into the next opening would be saved by a
    // reviewer who believed they were correcting the stored text and never saw that they were not.
    expect((get(fixture, 'edit-content') as HTMLTextAreaElement).value).toBe('Anzugsmoment 90 Nm');
  });

  it('sends the title and the fault code as they were typed into the dialog', async () => {
    const fixture = await renderModeration(http);
    await openEdit(fixture, http, roles);

    await type(fixture, 'edit-title', 'E-47 Druckabfall an der Presse');
    await type(fixture, 'edit-error-code', 'E-47B');
    await type(fixture, 'edit-content', 'Anzugsmoment 120 Nm');
    await type(fixture, 'edit-comment', 'Fehlercode war zu grob');
    await click(fixture, 'edit-save');

    const request = http.expectOne('/api/moderation/protocols/p-1');
    // Title and code are EDITABLE, unlike machine and type — so what the fields hold has to be what
    // is sent. A dialog that displayed an edit and submitted the old value would be the quietest
    // possible way to lose a correction.
    expect(request.request.body).toMatchObject({
      title: 'E-47 Druckabfall an der Presse',
      errorCode: 'E-47B',
    });
    request.flush({ id: 'p-1', status: 'RECEIVED' });
    http.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();
  });

  it('withdraws from inside the viewer, where the protocol was just read', async () => {
    const fixture = await renderModeration(http);
    await openViewer(fixture, http);

    await click(fixture, 'viewer-withdraw');

    // The viewer steps aside and the reason is asked for: the decision belongs where the evidence
    // is, but withdrawing still costs a sentence, because the next reader is owed the direction.
    expect(find(fixture, 'protocol-dialog')).toBeNull();
    expect(get(fixture, 'withdraw-target').textContent).toContain('E-47 Druckabfall');

    await click(fixture, 'withdraw-cancel');
  });

  it('approves from inside the viewer, and asks the corpus first', async () => {
    const fixture = await renderModeration(http, unapproved());
    await openViewer(fixture, http);

    await click(fixture, 'viewer-approve');
    noSimilar(http);
    await fixture.whenStable();

    // The duplicate check runs from the viewer exactly as it runs from the row: an approval made
    // after reading the protocol must not skip the question an approval made from the list asks.
    http.expectOne('/api/moderation/protocols/p-1/approval').flush({
      state: 'APPROVED',
      approvedBy: 'admin',
      approvedAt: '2026-08-21T09:00:00Z',
    });
    http.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();

    expect(get(fixture, 'approval-notice').textContent).toContain('E-47 Druckabfall');
  });

  it('closing the duplicate list abandons the approval it was asked about', async () => {
    const fixture = await renderModeration(http, unapproved());

    await click(fixture, 'row-approve');
    const report: SimilarityReport = {
      comparable: true,
      candidates: [
        {
          id: 'p-9',
          title: 'E-47 erneut',
          incidentDate: '2026-08-09',
          uploadedBy: 'schichtleiter',
          uploadedAt: '2026-08-09T10:00:00Z',
          similarity: 0.95,
          approval: { state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-10T09:00:00Z' },
        },
      ],
      total: 1,
      allIds: ['p-9'],
      threshold: 0.92,
    };
    http.expectOne('/api/moderation/protocols/p-1/similar').flush(report);
    await fixture.whenStable();

    expect(find(fixture, 'duplicates-dialog')).not.toBeNull();

    await click(fixture, 'duplicates-close');

    // Closing is the third answer the dialog has to accept, beside approve and open-a-candidate:
    // "I have seen enough and I am not deciding now". Nothing is sent — verify() enforces it — and
    // the row is still unapproved, waiting.
    expect(find(fixture, 'duplicates-dialog')).toBeNull();
    expect(find(fixture, 'row-approve')).not.toBeNull();
  });
});
