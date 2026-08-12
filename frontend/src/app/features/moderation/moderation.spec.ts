import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ArchivedProtocol, ModeratedProtocol, ProtocolPage } from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Moderation } from './moderation';

/**
 * The admin's view of the corpus.
 *
 * What matters here is that a deletion is deliberate and that it says what it is deleting: the
 * button that opens the confirmation is one of ten identical ones in a row, so a dialog that only
 * asked "are you sure" would be how the wrong protocol gets removed.
 */
describe('Moderation', () => {
  let httpMock: HttpTestingController;

  function protocol(overrides: Partial<ModeratedProtocol> = {}): ModeratedProtocol {
    return {
      id: 'p-1',
      machineNo: 'PR-03',
      title: 'E-47 Druckabfall',
      protocolType: 'STOERUNG',
      errorCode: 'E-47',
      uploadedBy: 'schichtleiter',
      uploadedAt: '2026-08-08T10:15:00Z',
      status: 'INDEXED',
      chunkCount: 2,
      // Approved by default, like 150 of the 165 seeded protocols: a fixture that defaulted to
      // UNAPPROVED would make every existing test a test of the queue.
      approval: { state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-11T09:00:00Z' },
      ...overrides,
    };
  }

  function page(items: ModeratedProtocol[], total = items.length, index = 0): ProtocolPage {
    return { items, page: index, size: 5, total };
  }

  /**
   * The signed-in roles, as a signal the tests can flip.
   *
   * <p>The view's buttons depend on them since v1.2 — the admin approves and no longer corrects,
   * the Schichtleiter corrects and does not approve — so a role is now an input to this component
   * rather than a fact about the route it lives on. A stub of {@link AuthService} rather than one of
   * `OAuthService`: what these tests are about is which buttons a role sees, and a fake token would
   * put base64 in the way of saying so.
   */
  let roles: ReturnType<typeof signal<string[]>>;

  beforeEach(async () => {
    roles = signal<string[]>(['admin']);

    await TestBed.configureTestingModule({
      imports: [Moderation],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { realmRoles: roles } },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use('de');
  });

  afterEach(() => httpMock.verify());

  /** Creates the component and answers the page and the machine list it loads on construction. */
  async function render(first: ProtocolPage = page([protocol()])) {
    const fixture = TestBed.createComponent(Moderation);
    httpMock
      .expectOne((request) => request.url === '/api/moderation/protocols')
      .flush(first);
    httpMock.expectOne('/api/machines').flush([
      { id: 'm-1', machineNo: 'PR-03', name: 'Presse 3', type: 'Presse', location: 'Halle 1' },
      { id: 'm-2', machineNo: 'AB-02', name: 'Abfüller 2', type: 'Abfüller', location: 'Halle 2' },
    ]);
    await fixture.whenStable();
    return fixture;
  }

  /** Fills the filter form the way a user does, so the disabled rule is exercised for real. */
  function type(element: HTMLElement, testId: string, value: string): void {
    const field = element.querySelector(`[data-testid="${testId}"]`) as
      | HTMLInputElement
      | HTMLSelectElement
      | HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'));
  }

  it('lists the corpus with the author of each protocol', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelectorAll('[data-testid="moderation-row"]').length).toBe(1);
    expect(element.querySelector('[data-testid="moderation-table"]')?.textContent).toContain(
      'E-47 Druckabfall',
    );
    // The accountability half of ADR-006: a reviewer who cannot see who filed a protocol can
    // remove the text and learn nothing.
    expect(element.querySelector('[data-testid="row-uploader"]')?.textContent).toContain(
      'schichtleiter',
    );
  });

  it('asks for the first page with a bounded size, and for no filter', async () => {
    const fixture = TestBed.createComponent(Moderation);
    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');

    // Paged, not "everything": the corpus grows every time the feature it moderates is used.
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('5');
    // An empty field is a field nobody filled in, so it is left off the query string rather than
    // sent as "" — the backend would have to interpret that instead of reading it.
    expect(request.request.params.has('machineNo')).toBe(false);
    expect(request.request.params.has('titleContains')).toBe(false);
    request.flush(page([protocol()]));
    httpMock.expectOne('/api/machines').flush([]);
    await fixture.whenStable();
  });

  it('names the protocol in the delete confirmation, and deletes nothing until confirmed', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    const target = element.querySelector('[data-testid="delete-target"]')?.textContent ?? '';
    expect(target).toContain('E-47 Druckabfall');
    expect(target).toContain('PR-03');
    // The copy is the ADR-006 revision in one sentence: gone for everyone at once, no restore, and
    // still readable in the archive.
    const dialog = element.querySelector('[data-testid="delete-confirm-dialog"]')?.textContent ?? '';
    expect(dialog).toContain('kein Wiederherstellen');
    expect(dialog).toContain('Gelöschte Protokolle');
    // httpMock.verify() in afterEach proves no DELETE was sent by opening the dialog.
  });

  it('will not delete without a stated reason', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // Required here as well as on the server. A removal from the corpus with no reason is exactly
    // the unexplained change the audit trail exists to make visible.
    expect(
      (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(element.querySelector('[data-testid="delete-reason-required"]')).not.toBeNull();

    type(element, 'delete-comment', 'erfundene Massnahme');
    await fixture.whenStable();

    expect(
      (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement).disabled,
    ).toBe(false);
  });

  it('cancelling the confirmation deletes nothing', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    (element.querySelector('[data-testid="delete-cancel"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="delete-confirm-dialog"]')).toBeNull();
  });

  it('deletes on confirmation, then reloads the list and says what went', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    type(element, 'delete-comment', 'erfundene Massnahme');
    await fixture.whenStable();
    (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement).click();

    const request = httpMock.expectOne('/api/moderation/protocols/p-1');
    expect(request.request.method).toBe('DELETE');
    // In the body, not the query string: a sentence about a named colleague's mistake would
    // otherwise be written into access logs, proxy logs and browser history.
    expect(request.request.body).toEqual({ comment: 'erfundene Massnahme' });
    request.flush(null, { status: 204, statusText: 'No Content' });

    // Reloaded rather than spliced: a delete changes the total, and therefore which rows belong
    // on this page.
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([], 0));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="removed-notice"]')?.textContent).toContain(
      'E-47 Druckabfall',
    );
    expect(element.querySelector('[data-testid="moderation-empty"]')).not.toBeNull();
  });

  it('opens a protocol through the moderation path, not the shop-floor one', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-open"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // An admin holds no shop-floor role, so /api/protocols/{id}/document would 403 for exactly
    // the reader this dialog was reused for.
    const request = httpMock.expectOne('/api/moderation/protocols/p-1/document');
    request.flush(new Blob(['Symptom:\nKein Druck.\n'], { type: 'text/plain' }));
    await fixture.whenStable();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="protocol-dialog"]')).not.toBeNull();
  });

  it('pages forward and back, and disables the ends', async () => {
    const fixture = await render(page([protocol()], 25, 0));
    const element = fixture.nativeElement as HTMLElement;

    expect((element.querySelector('[data-testid="page-previous"]') as HTMLButtonElement).disabled)
      .toBe(true);
    expect(element.querySelector('[data-testid="page-state"]')?.textContent).toContain('25');

    (element.querySelector('[data-testid="page-next"]') as HTMLButtonElement).click();
    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');
    expect(request.request.params.get('page')).toBe('1');
    request.flush(page([protocol({ id: 'p-2', title: 'Zweite Seite' })], 25, 1));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="moderation-table"]')?.textContent).toContain(
      'Zweite Seite',
    );
    expect((element.querySelector('[data-testid="page-previous"]') as HTMLButtonElement).disabled)
      .toBe(false);
  });

  it('renders protocol dates in the interface language', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="moderation-table"]')?.textContent).toContain(
      '08.08.2026',
    );

    TestBed.inject(I18nService).use('en');
    await fixture.whenStable();

    // The same instant, in the format the chosen language uses — not the browser's.
    expect(element.querySelector('[data-testid="moderation-table"]')?.textContent).toContain(
      '08/08/2026',
    );
  });

  it('names the expected date format, because the native inputs follow the browser', async () => {
    const fixture = await render();

    // The one place the interface language does not decide: a native date input is drawn by the
    // browser in the browser's locale. Naming the format is cheaper than a custom picker.
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="filter-date-format"]')
        ?.textContent,
    ).toContain('TT.MM.JJJJ');
  });

  it('keeps the title and date fields out of reach until a machine is chosen', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // The owner's noise rule, and the backend's 400: across ten machines a title fragment answers
    // with rows from machines the reviewer was not looking at.
    for (const id of ['filter-title', 'filter-from', 'filter-to']) {
      expect((element.querySelector(`[data-testid="${id}"]`) as HTMLInputElement).disabled, id)
        .toBe(true);
    }
    // And a disabled field with no explanation reads as broken rather than as not-yet-applicable.
    expect(element.querySelector('[data-testid="filter-hint"]')?.textContent).toContain('Maschine');

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();

    for (const id of ['filter-title', 'filter-from', 'filter-to']) {
      expect((element.querySelector(`[data-testid="${id}"]`) as HTMLInputElement).disabled, id)
        .toBe(false);
    }
  });

  it('sends machine, title and both dates, and goes back to the first page', async () => {
    const fixture = await render(page([protocol()], 25, 1));
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();
    type(element, 'filter-title', 'sensor');
    type(element, 'filter-from', '2026-08-01');
    type(element, 'filter-to', '2026-08-31');
    await fixture.whenStable();
    (element.querySelector('[data-testid="filter-apply"]') as HTMLButtonElement).click();

    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');
    expect(request.request.params.get('machineNo')).toBe('PR-03');
    expect(request.request.params.get('titleContains')).toBe('sensor');
    expect(request.request.params.get('from')).toBe('2026-08-01');
    expect(request.request.params.get('to')).toBe('2026-08-31');
    // Page 3 of the old result is not page 3 of the new one.
    expect(request.request.params.get('page')).toBe('0');
    request.flush(page([protocol()], 1));
    await fixture.whenStable();
  });

  it('keeps the filter while paging through what it matched', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();
    (element.querySelector('[data-testid="filter-apply"]') as HTMLButtonElement).click();
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()], 25));
    await fixture.whenStable();

    (element.querySelector('[data-testid="page-next"]') as HTMLButtonElement).click();

    // A "Weiter" that dropped the filter would show page 2 of a different list.
    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.get('machineNo')).toBe('PR-03');
    request.flush(page([protocol({ id: 'p-2' })], 25, 1));
    await fixture.whenStable();
  });

  it('clears the dependent fields when the machine is cleared', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();
    type(element, 'filter-title', 'sensor');
    await fixture.whenStable();
    type(element, 'filter-machine', '');
    await fixture.whenStable();

    // A title left behind in a field the user can no longer reach is a filter that lies about what
    // it filters — and the request it would build is the one the backend answers with a 400.
    expect((element.querySelector('[data-testid="filter-title"]') as HTMLInputElement).value).toBe(
      '',
    );
  });

  it('resets back to the unfiltered corpus', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();
    (element.querySelector('[data-testid="filter-apply"]') as HTMLButtonElement).click();
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();

    (element.querySelector('[data-testid="filter-reset"]') as HTMLButtonElement).click();

    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');
    expect(request.request.params.has('machineNo')).toBe(false);
    request.flush(page([protocol()]));
    await fixture.whenStable();

    expect((element.querySelector('[data-testid="filter-machine"]') as HTMLSelectElement).value)
      .toBe('');
  });

  it('says a filtered result is empty differently from an empty corpus, and offers the way back', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-machine', 'AB-02');
    await fixture.whenStable();
    (element.querySelector('[data-testid="filter-apply"]') as HTMLButtonElement).click();
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([], 0));
    await fixture.whenStable();

    // An empty corpus is a fact; an empty filtered result is something the reader can undo.
    expect(element.querySelector('[data-testid="moderation-empty"]')?.textContent).toContain(
      'Filter',
    );
    expect(element.querySelector('[data-testid="empty-reset"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="moderation-table"]')).toBeNull();
  });

  it('loses the dropdown, not the page, when the machine list cannot be read', async () => {
    const fixture = TestBed.createComponent(Moderation);
    httpMock
      .expectOne((r) => r.url === '/api/moderation/protocols')
      .flush(page([protocol()]));
    httpMock.expectOne('/api/machines').flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    // The corpus list is the view; the filter is the extra on top of it.
    expect(element.querySelector('[data-testid="filter-machines-error"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="moderation-table"]')).not.toBeNull();
  });

  // -------------------------------------------------------------------------------------------
  // Correction
  // -------------------------------------------------------------------------------------------

  /**
   * Opens the edit dialog on the first row and answers the document fetch it makes.
   *
   * AS THE SCHICHTLEITER, because since 2026-08-13 correcting is theirs and the administrator has
   * no Bearbeiten button at all. The role is set here rather than in each test below so the tests
   * keep saying what they were always about — what the correction dialog does — rather than
   * repeating who is allowed to open it. That rule has its own test.
   */
  async function openEdit(fixture: Awaited<ReturnType<typeof render>>, text = 'Anzugsmoment 90 Nm') {
    const element = fixture.nativeElement as HTMLElement;
    roles.set(['schichtleiter']);
    await fixture.whenStable();
    (element.querySelector('[data-testid="row-edit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    httpMock
      .expectOne('/api/moderation/protocols/p-1/document')
      .flush(new Blob([text], { type: 'text/plain' }));
    await fixture.whenStable();
    await fixture.whenStable();
    return element;
  }

  it('loads the stored text into the edit dialog rather than inventing it', async () => {
    const fixture = await render();
    const element = await openEdit(fixture);

    // The thing being corrected is the document on the volume. A form pre-filled from anything
    // else would silently replace the text with whatever the row happened to know.
    expect((element.querySelector('[data-testid="edit-content"]') as HTMLTextAreaElement).value)
      .toContain('90 Nm');
    expect((element.querySelector('[data-testid="edit-title"]') as HTMLInputElement).value)
      .toBe('E-47 Druckabfall');
  });

  it('shows machine and type as locked, and says why', async () => {
    const fixture = await render();
    const element = await openEdit(fixture);

    // Shown, because a reviewer has to see what they are correcting; not editable, because a
    // protocol's machine is its provenance rather than its content (ADR-006 revision).
    expect(element.querySelector('[data-testid="edit-machine"]')?.textContent).toContain('PR-03');
    expect(element.querySelector('[data-testid="edit-type"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="edit-machine"] input')).toBeNull();
    expect(element.querySelector('[data-testid="edit-locked-hint"]')?.textContent).toContain(
      'nicht änderbar',
    );
  });

  it('will not save a correction without a reason', async () => {
    const fixture = await render();
    const element = await openEdit(fixture);

    expect((element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).disabled)
      .toBe(true);

    type(element, 'edit-comment', 'Drehmoment korrigiert');
    await fixture.whenStable();

    expect((element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).disabled)
      .toBe(false);
  });

  it('sends the correction with the unchanged machine, and says it is being re-indexed', async () => {
    const fixture = await render();
    const element = await openEdit(fixture);

    type(element, 'edit-content', 'Anzugsmoment 120 Nm');
    type(element, 'edit-comment', 'Drehmoment korrigiert');
    await fixture.whenStable();
    (element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).click();

    const request = httpMock.expectOne('/api/moderation/protocols/p-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toMatchObject({
      // Echoed back unchanged. The backend refuses a different one; sending it is what lets the
      // backend refuse rather than guess.
      machineNo: 'PR-03',
      protocolType: 'STOERUNG',
      content: 'Anzugsmoment 120 Nm',
      comment: 'Drehmoment korrigiert',
    });
    request.flush({ id: 'p-1', status: 'RECEIVED', message: 'ok' }, { status: 202, statusText: 'Accepted' });

    // The list is reloaded because the row's title may have changed with it.
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();

    // 202, not 200: the text is corrected and the index catches up in a moment. Saying so is the
    // same honesty the upload view owes its own 202.
    expect(element.querySelector('[data-testid="corrected-notice"]')?.textContent).toContain(
      'neu indexiert',
    );
  });

  it('explains a refused identity change instead of failing generically', async () => {
    const fixture = await render();
    const element = await openEdit(fixture);

    type(element, 'edit-comment', 'verschieben');
    await fixture.whenStable();
    (element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).click();
    httpMock.expectOne('/api/moderation/protocols/p-1').flush(
      { reason: 'PROTOCOL_IDENTITY_LOCKED', error: 'machine cannot be changed' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    // Matched on the stable code rather than the English sentence, like every other guard.
    expect(element.querySelector('[data-testid="edit-failure"]')?.textContent).toContain(
      'löschen und neu anlegen',
    );
  });

  it('says plainly when a protocol was archived under the editor', async () => {
    const fixture = await render();
    const element = await openEdit(fixture);

    type(element, 'edit-comment', 'zu spaet');
    await fixture.whenStable();
    (element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).click();
    httpMock.expectOne('/api/moderation/protocols/p-1').flush(
      { reason: 'PROTOCOL_ARCHIVED', error: 'archived' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="edit-failure"]')?.textContent).toContain(
      'endgültig',
    );
  });

  // -------------------------------------------------------------------------------------------
  // Approval (v1.2)
  // -------------------------------------------------------------------------------------------

  const UNAPPROVED = protocol({
    approval: { state: 'UNAPPROVED', approvedBy: null, approvedAt: null },
  });

  it('shows the approval state of every row, with who approved it and when', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // On EVERY row, not only on the queue: this list is also where an administrator checks that
    // something they approved is still approved, and after a correction it will not be.
    const cell = element.querySelector('[data-testid="approval-state"]')?.textContent ?? '';
    expect(cell).toContain('Freigegeben');
    expect(cell).toContain('admin');
    expect(cell).toContain('11.08.2026');
  });

  it('filters to the approval queue without a machine, and without a second button press', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-approval', 'UNAPPROVED');
    await fixture.whenStable();

    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');
    // NOT subject to the machine-first rule the other three filters obey. That rule exists because
    // a title fragment across ten machines answers with rows nobody was looking for; a review queue
    // is the opposite, and is only useful whole.
    expect(request.request.params.get('approvalState')).toBe('UNAPPROVED');
    expect(request.request.params.has('machineNo')).toBe(false);
    expect(request.request.params.get('page')).toBe('0');
    request.flush(page([UNAPPROVED]));
    await fixture.whenStable();
  });

  it('keeps the queue filter when the machine is cleared', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    type(element, 'filter-approval', 'UNAPPROVED');
    await fixture.whenStable();
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([UNAPPROVED]));
    await fixture.whenStable();

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();
    type(element, 'filter-machine', '');
    await fixture.whenStable();

    // Clearing the machine clears what DEPENDED on it. The approval filter never did — the backend
    // applies it without one — so resetting it here would mean "all machines" silently emptying the
    // reviewer's queue: a filter clearing a filter it has nothing to do with.
    expect(
      (element.querySelector('[data-testid="filter-approval"]') as HTMLSelectElement).value,
    ).toBe('UNAPPROVED');
  });

  it('approves without a dialog, and reloads the page it changed', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    const request = httpMock.expectOne('/api/moderation/protocols/p-1/approval');
    expect(request.request.method).toBe('PUT');
    // A state asserted rather than a verb performed, so a double click is harmless and writes no
    // second audit row. No comment: approving affirms the text as it stands.
    expect(request.request.body).toEqual({ approved: true, comment: '' });
    request.flush({ state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-12T10:00:00Z' });

    // Reloaded rather than patched in place: with the queue as the active filter the row no longer
    // belongs on this page at all, and a row that stayed would describe a list it has just left.
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="approval-notice"]')?.textContent).toContain(
      'E-47 Druckabfall',
    );
  });

  it('explains the four-eyes refusal beside the row, instead of failing generically', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    httpMock.expectOne('/api/moderation/protocols/p-1/approval').flush(
      { reason: 'FOUR_EYES_REQUIRED', error: 'you corrected this protocol' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    // THE SENTENCE IS THE POINT. A disabled button, or "the request failed", would leave an
    // administrator staring at a control that does not work for a reason they cannot discover —
    // which is a bug report waiting to happen rather than a rule being enforced.
    const failure = element.querySelector('[data-testid="approval-failure"]')?.textContent ?? '';
    expect(failure).toContain('zuletzt korrigiert');
    expect(failure).toContain('andere');
    // Beside the row it belongs to: the reader clicked one button among ten identical ones.
    expect(element.querySelector('[data-testid="approval-failure-row"]')).not.toBeNull();
  });

  it('will not withdraw an approval without a stated reason', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-withdraw"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // Required here as well as on the server, and enforced BEFORE the click rather than by a 400
    // afterwards. Withdrawing takes back what a named person vouched for; the next reader is owed
    // the reason.
    expect(element.querySelector('[data-testid="withdraw-target"]')?.textContent).toContain(
      'E-47 Druckabfall',
    );
    expect(element.querySelector('[data-testid="withdraw-reason-required"]')).not.toBeNull();
    expect(
      (element.querySelector('[data-testid="withdraw-confirm-button"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    httpMock.verify();
  });

  it('withdraws with the reason in the body, and says what changed', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-withdraw"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    type(element, 'withdraw-comment', 'Massnahme passt nicht zur Ursache');
    await fixture.whenStable();
    (element.querySelector('[data-testid="withdraw-confirm-button"]') as HTMLButtonElement).click();

    const request = httpMock.expectOne('/api/moderation/protocols/p-1/approval');
    // In the body for the same reason the delete comment is: it can name a colleague's mistake,
    // and a query string lands in access logs.
    expect(request.request.body).toEqual({
      approved: false,
      comment: 'Massnahme passt nicht zur Ursache',
    });
    request.flush({ state: 'UNAPPROVED', approvedBy: null, approvedAt: null });
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([UNAPPROVED]));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="approval-notice"]')?.textContent).toContain(
      'zurückgezogen',
    );
  });

  it('offers the decision in the viewer, where the evidence is', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-open"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    httpMock
      .expectOne('/api/moderation/protocols/p-1/document')
      .flush(new Blob(['Symptom:\nKein Druck.\n'], { type: 'text/plain' }));
    await fixture.whenStable();
    await fixture.whenStable();

    // A reviewer who has just read the protocol should not have to close the dialog and find the
    // row again among ten — that is the step at which the wrong row gets approved. The state is in
    // the head as well, so it is read before the text rather than looked up afterwards.
    expect(element.querySelector('[data-testid="protocol-machine"]')?.textContent).toContain(
      'Nicht freigegeben',
    );
    expect(element.querySelector('[data-testid="viewer-approve"]')).not.toBeNull();
  });

  // -------------------------------------------------------------------------------------------
  // Who may do what — the role split of 2026-08-13
  // -------------------------------------------------------------------------------------------

  it('gives the administrator no correction button — the approver does not correct', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // The endpoint answers 403 for an admin since 2026-08-13. A button left on screen would offer
    // an action that cannot succeed, and a control that fails reads as a broken application rather
    // than as a job belonging to somebody else.
    expect(element.querySelector('[data-testid="row-edit"]')).toBeNull();
    expect(element.querySelector('[data-testid="row-withdraw"]')).not.toBeNull();
  });

  it('gives the Schichtleiter the correction button and no approval control', async () => {
    // THE OTHER HALF OF THE SAME RULE. The corrector is never the approver, so this role sees
    // Bearbeiten and neither Freigeben nor Freigabe zurückziehen.
    //
    // NOTE, AND IT IS A FINDING RATHER THAN A GAP IN THE TEST: /moderation is guarded by
    // roleGuard('admin'), so no Schichtleiter can reach this view today and the correction path has
    // no interface at all. Widening that route is a permission decision for Carlos and is
    // deliberately not made here. This test is what makes the button correct the day it is.
    roles.set(['schichtleiter']);
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="row-edit"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="row-approve"]')).toBeNull();
    expect(element.querySelector('[data-testid="row-withdraw"]')).toBeNull();
  });

  // -------------------------------------------------------------------------------------------
  // The archive
  // -------------------------------------------------------------------------------------------

  /** Switches to the archive tab and answers the page it loads. */
  async function openArchive(
    fixture: Awaited<ReturnType<typeof render>>,
    items: Partial<ArchivedProtocol>[] = [{}],
  ) {
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('[data-testid="tab-archive"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols/deleted');
    request.flush({
      items: items.map((overrides) => ({
        id: 'a-1',
        machineNo: 'PR-03',
        title: 'Erfundene Massnahme',
        protocolType: 'STOERUNG',
        errorCode: 'E-47',
        uploadedBy: 'schichtleiter',
        uploadedAt: '2026-08-08T10:15:00Z',
        deletedAt: '2026-08-09T08:00:00Z',
        deletedBy: 'admin',
        deleteComment: 'Drehmoment war frei erfunden',
        ...overrides,
      })),
      page: 0,
      size: 5,
      total: items.length,
      cap: 50,
    });
    await fixture.whenStable();
    return { element, request };
  }

  it('shows what was removed, by whom and why', async () => {
    const fixture = await render();
    const { element } = await openArchive(fixture);

    expect(element.querySelector('[data-testid="archive-actor"]')?.textContent).toContain('admin');
    // The reason is the field the whole archive exists to carry.
    expect(element.querySelector('[data-testid="archive-comment"]')?.textContent).toContain(
      'frei erfunden',
    );
    // The live corpus table is not on screen at the same time.
    expect(element.querySelector('[data-testid="moderation-table"]')).toBeNull();
  });

  it('states the cap and that there is no restore', async () => {
    const fixture = await render();
    const { element } = await openArchive(fixture);

    // Both are decisions, and a reviewer who assumes deletions are recoverable uses deletion
    // differently. The cap comes from the backend so the sentence cannot drift from the rule.
    const hint = element.querySelector('[data-testid="archive-hint"]')?.textContent ?? '';
    expect(hint).toContain('50');
    expect(hint).toContain('Kein Wiederherstellen');
  });

  it('offers no way to restore or edit an archived protocol', async () => {
    const fixture = await render();
    const { element } = await openArchive(fixture);

    const row = element.querySelector('[data-testid="archive-row"]') as HTMLElement;
    expect(row.querySelector('[data-testid="archive-open"]')).not.toBeNull();
    // Reading it is the whole affordance. Undelete would make the archive a staging area for
    // putting bad protocols back (ADR-006 revision).
    expect(row.textContent).not.toContain('Wiederherstellen');
    expect(row.querySelector('[data-testid="row-edit"]')).toBeNull();
    expect(row.querySelector('[data-testid="row-delete"]')).toBeNull();
  });

  it('reads an archived document through the archive endpoint, not the live one', async () => {
    const fixture = await render();
    const { element } = await openArchive(fixture);

    (element.querySelector('[data-testid="archive-open"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // The live paths answer 404 for a removed protocol; this is the only door back to its content.
    httpMock
      .expectOne('/api/moderation/protocols/deleted/a-1/document')
      .flush(new Blob(['Symptom:\nErfunden.\n'], { type: 'text/plain' }));
    await fixture.whenStable();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="protocol-dialog"]')).not.toBeNull();
  });

  it('filters the archive by machine and pages within it', async () => {
    const fixture = await render();
    const { element } = await openArchive(fixture);

    type(element, 'archive-machine', 'AB-02');
    await fixture.whenStable();

    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols/deleted');
    expect(request.request.params.get('machineNo')).toBe('AB-02');
    expect(request.request.params.get('page')).toBe('0');
    request.flush({ items: [], page: 0, size: 5, total: 0, cap: 50 });
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="archive-empty"]')).not.toBeNull();
  });

  it('reports a protocol someone else already deleted, rather than failing generically', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    type(element, 'delete-comment', 'weg damit');
    await fixture.whenStable();
    (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement).click();
    httpMock
      .expectOne('/api/moderation/protocols/p-1')
      .flush({}, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="moderation-failure"]')?.textContent).toContain(
      'nicht mehr im Bestand',
    );
  });
});
