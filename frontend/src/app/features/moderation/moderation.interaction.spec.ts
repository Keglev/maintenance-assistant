import { HttpTestingController } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  Roles,
  archivePage,
  archived,
  click,
  find,
  get,
  noSimilar,
  openArchive,
  openEdit,
  page,
  protocol,
  renderModeration,
  setUpModeration,
  tearDownModeration,
  type,
} from './moderation.fixtures';

/**
 * The moderation screen driven the way a reviewer drives it: tabs, pagination and the notices that
 * report what just happened.
 *
 * <p>The contract under test is that every control the template offers actually does something —
 * these are the listeners a test calling component methods never touches, and the survey found them
 * uncovered as a block.
 *
 * <p>OUT OF SCOPE: the dialogs (moderation.dialogs.spec.ts), the failure paths
 * (moderation.failures.spec.ts) and everything moderation.spec.ts already covers — listing,
 * filtering, deleting, approving, role gating.
 *
 * <p>SIBLINGS: moderation.spec.ts, moderation.dialogs.spec.ts, moderation.failures.spec.ts, all
 * arranged by moderation.fixtures.ts.
 */
describe('Moderation — tabs, paging and notices', () => {
  let http: HttpTestingController;
  let roles: Roles;

  beforeEach(async () => {
    ({ http, roles } = await setUpModeration());
  });

  afterEach(() => tearDownModeration(http));

  it('comes back to the corpus from the archive', async () => {
    const fixture = await renderModeration(http);
    await openArchive(fixture, http);

    expect(find(fixture, 'archive-table')).not.toBeNull();

    await click(fixture, 'tab-corpus');

    // Back on the corpus, and the archive is gone rather than merely hidden behind it: a reviewer
    // who returns to the list must not be reading removed protocols as if they were current.
    expect(find(fixture, 'moderation-table')).not.toBeNull();
    expect(find(fixture, 'archive-table')).toBeNull();
  });

  it('re-reads the archive on every arrival rather than showing what it read before', async () => {
    const fixture = await renderModeration(http);
    await openArchive(fixture, http, archivePage([archived({ title: 'Vor dem Wechsel' })]));
    await click(fixture, 'tab-corpus');

    await openArchive(fixture, http, archivePage([archived({ title: 'Inzwischen entfernt' })]));

    // ON ARRIVAL, not once: the archive is not loaded at construction, so an administrator who
    // never opens it pays nothing — and one who comes back to it sees what has been removed since,
    // rather than a list that went stale the moment they left the tab. Both halves of that are the
    // same decision, and this is the half a cache would quietly undo.
    expect(get(fixture, 'archive-table').textContent).toContain('Inzwischen entfernt');
    expect(get(fixture, 'archive-table').textContent).not.toContain('Vor dem Wechsel');
  });

  it('pages forward and then back to where it started', async () => {
    const first = page([protocol({ id: 'p-1', title: 'Erste Seite' })], 10);
    const fixture = await renderModeration(http, first);

    await click(fixture, 'page-next');
    http
      .expectOne((request) => request.url === '/api/moderation/protocols')
      .flush(page([protocol({ id: 'p-6', title: 'Zweite Seite' })], 10, 1));
    await fixture.whenStable();
    expect(get(fixture, 'moderation-table').textContent).toContain('Zweite Seite');

    await click(fixture, 'page-previous');
    const back = http.expectOne((request) => request.url === '/api/moderation/protocols');
    // Page 0 asked for again, rather than the component keeping the first page in memory: a row
    // deleted from another tab would otherwise reappear on the way back.
    expect(back.request.params.get('page')).toBe('0');
    back.flush(first);
    await fixture.whenStable();

    expect(get(fixture, 'moderation-table').textContent).toContain('Erste Seite');
    expect((get(fixture, 'page-previous') as HTMLButtonElement).disabled).toBe(true);
  });

  it('pages through the archive and stops at both of its ends', async () => {
    const fixture = await renderModeration(http);
    await openArchive(
      fixture,
      http,
      archivePage([archived({ id: 'a-1', title: 'Ältester Eintrag' })], 10),
    );

    expect((get(fixture, 'archive-previous') as HTMLButtonElement).disabled).toBe(true);

    await click(fixture, 'archive-next');
    const forward = http.expectOne(
      (request) => request.url === '/api/moderation/protocols/deleted',
    );
    expect(forward.request.params.get('page')).toBe('1');
    forward.flush(archivePage([archived({ id: 'a-9', title: 'Jüngerer Eintrag' })], 10, 1));
    await fixture.whenStable();
    expect(get(fixture, 'archive-table').textContent).toContain('Jüngerer Eintrag');

    await click(fixture, 'archive-previous');
    const backward = http.expectOne(
      (request) => request.url === '/api/moderation/protocols/deleted',
    );
    expect(backward.request.params.get('page')).toBe('0');
    backward.flush(archivePage([archived({ id: 'a-1', title: 'Ältester Eintrag' })], 10));
    await fixture.whenStable();

    expect(get(fixture, 'archive-table').textContent).toContain('Ältester Eintrag');
    expect((get(fixture, 'archive-next') as HTMLButtonElement).disabled).toBe(false);
  });

  it('dismisses the notice that named what was removed', async () => {
    const fixture = await renderModeration(http);

    await click(fixture, 'row-delete');
    await type(fixture, 'delete-comment', 'doppelt erfasst');
    await click(fixture, 'delete-confirm-button');
    http.expectOne('/api/moderation/protocols/p-1').flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    http.expectOne((request) => request.url === '/api/moderation/protocols').flush(page([], 0));
    await fixture.whenStable();

    expect(get(fixture, 'removed-notice').textContent).toContain('E-47 Druckabfall');

    await click(fixture, 'removed-dismiss');

    // Dismissible because it is a receipt, not a warning: it has been read, and a notice that
    // cannot be cleared stays on screen through the next three actions it does not describe.
    expect(find(fixture, 'removed-notice')).toBeNull();
  });

  it('dismisses the notice that reported an approval', async () => {
    const fixture = await renderModeration(
      http,
      page([protocol({ approval: { state: 'UNAPPROVED', approvedBy: null, approvedAt: null } })]),
    );

    await click(fixture, 'row-approve');
    noSimilar(http);
    await fixture.whenStable();
    http.expectOne('/api/moderation/protocols/p-1/approval').flush({
      state: 'APPROVED',
      approvedBy: 'admin',
      approvedAt: '2026-08-21T09:00:00Z',
    });
    http
      .expectOne((request) => request.url === '/api/moderation/protocols')
      .flush(page([protocol()]));
    await fixture.whenStable();

    expect(get(fixture, 'approval-notice').textContent).toContain('E-47 Druckabfall');

    await click(fixture, 'approval-notice-dismiss');

    expect(find(fixture, 'approval-notice')).toBeNull();
  });

  it('dismisses the notice that reported a correction', async () => {
    const fixture = await renderModeration(http);
    await openEdit(fixture, http, roles);

    await type(fixture, 'edit-content', 'Anzugsmoment 120 Nm');
    await type(fixture, 'edit-comment', 'Drehmoment war falsch');
    await click(fixture, 'edit-save');
    http.expectOne('/api/moderation/protocols/p-1').flush({ id: 'p-1', status: 'RECEIVED' });
    http
      .expectOne((request) => request.url === '/api/moderation/protocols')
      .flush(page([protocol()]));
    await fixture.whenStable();

    expect(get(fixture, 'corrected-notice').textContent).toContain('E-47 Druckabfall');

    await click(fixture, 'corrected-dismiss');

    expect(find(fixture, 'corrected-notice')).toBeNull();
  });

  it('offers the way out of a filter that matched nothing, and takes it', async () => {
    const fixture = await renderModeration(http);

    await type(fixture, 'filter-machine', 'AB-02');
    await click(fixture, 'filter-apply');
    http.expectOne((request) => request.url === '/api/moderation/protocols').flush(page([], 0));
    await fixture.whenStable();

    expect(find(fixture, 'moderation-empty')).not.toBeNull();

    await click(fixture, 'empty-reset');
    const reset = http.expectOne((request) => request.url === '/api/moderation/protocols');
    // The whole corpus, unfiltered: the offer beside "nothing here" is only honest if pressing it
    // shows the reader something.
    expect(reset.request.params.has('machineNo')).toBe(false);
    reset.flush(page([protocol()]));
    await fixture.whenStable();

    expect(find(fixture, 'moderation-empty')).toBeNull();
    expect(get(fixture, 'moderation-table').textContent).toContain('E-47 Druckabfall');
  });
});
