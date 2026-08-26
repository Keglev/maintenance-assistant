import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  ArchivedProtocol,
  ModeratedProtocol,
  ProtocolPage,
  SimilarProtocol,
  SimilarityReport,
} from '../../core/api/api.types';
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
    httpMock.expectOne((request) => request.url === '/api/moderation/protocols').flush(first);
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
      HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'));
  }

  /**
   * Answers the duplicate check that every approval now makes first.
   *
   * <p>Since 2026-08-14 pressing Freigeben asks what the corpus already says before it approves. An
   * empty answer is the ordinary case — nothing similar, no dialog, one click, exactly the #54
   * behaviour — so most tests here just need it flushed and out of the way.
   */
  function noSimilar(id = 'p-1'): void {
    httpMock.expectOne(`/api/moderation/protocols/${id}/similar`).flush({
      comparable: true,
      candidates: [],
      total: 0,
      allIds: [],
      threshold: 0.92,
    });
  }

  /**
   * Answers the history the viewer now fetches whenever it opens on a moderation path.
   *
   * <p>Since 2026-08-14 the viewer carries a history section, so opening it is two requests rather
   * than one. What the section RENDERS is the viewer spec's business, in its own file; here it only
   * has to be answered so `httpMock.verify()` stays meaningful.
   */
  function noHistory(id: string): void {
    httpMock
      .expectOne(`/api/moderation/protocols/${id}/history`)
      .flush({ events: [], total: 0, limit: 3 });
  }

  /** One candidate above the threshold — the case that opens the dialog. */
  function similarTo(
    id = 'p-1',
    overrides: Partial<SimilarProtocol> = {},
    report: Partial<SimilarityReport> = {},
  ): void {
    const candidate: SimilarProtocol = {
      id: 'p-9',
      title: 'E-47 Druckabfall im Presshub',
      incidentDate: '2024-10-08',
      uploadedBy: 'techniker',
      uploadedAt: '2026-08-07T08:00:00Z',
      similarity: 0.9305,
      approval: { state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-11T09:00:00Z' },
      ...overrides,
    };
    httpMock.expectOne(`/api/moderation/protocols/${id}/similar`).flush({
      comparable: true,
      candidates: [candidate],
      total: 1,
      allIds: [candidate.id],
      threshold: 0.92,
      ...report,
    });
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
    const dialog =
      element.querySelector('[data-testid="delete-confirm-dialog"]')?.textContent ?? '';
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
      (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(element.querySelector('[data-testid="delete-reason-required"]')).not.toBeNull();

    type(element, 'delete-comment', 'erfundene Massnahme');
    await fixture.whenStable();

    expect(
      (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement)
        .disabled,
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
    noHistory('p-1');
    await fixture.whenStable();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="protocol-dialog"]')).not.toBeNull();
  });

  it('pages forward and back, and disables the ends', async () => {
    const fixture = await render(page([protocol()], 25, 0));
    const element = fixture.nativeElement as HTMLElement;

    expect(
      (element.querySelector('[data-testid="page-previous"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(element.querySelector('[data-testid="page-state"]')?.textContent).toContain('25');

    (element.querySelector('[data-testid="page-next"]') as HTMLButtonElement).click();
    const request = httpMock.expectOne((r) => r.url === '/api/moderation/protocols');
    expect(request.request.params.get('page')).toBe('1');
    request.flush(page([protocol({ id: 'p-2', title: 'Zweite Seite' })], 25, 1));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="moderation-table"]')?.textContent).toContain(
      'Zweite Seite',
    );
    expect(
      (element.querySelector('[data-testid="page-previous"]') as HTMLButtonElement).disabled,
    ).toBe(false);
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
      expect(
        (element.querySelector(`[data-testid="${id}"]`) as HTMLInputElement).disabled,
        id,
      ).toBe(true);
    }
    // And a disabled field with no explanation reads as broken rather than as not-yet-applicable.
    expect(element.querySelector('[data-testid="filter-hint"]')?.textContent).toContain('Maschine');

    type(element, 'filter-machine', 'PR-03');
    await fixture.whenStable();

    for (const id of ['filter-title', 'filter-from', 'filter-to']) {
      expect(
        (element.querySelector(`[data-testid="${id}"]`) as HTMLInputElement).disabled,
        id,
      ).toBe(false);
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

    expect(
      (element.querySelector('[data-testid="filter-machine"]') as HTMLSelectElement).value,
    ).toBe('');
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
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
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
  async function openEdit(
    fixture: Awaited<ReturnType<typeof render>>,
    text = 'Anzugsmoment 90 Nm',
  ) {
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
    expect(
      (element.querySelector('[data-testid="edit-content"]') as HTMLTextAreaElement).value,
    ).toContain('90 Nm');
    expect((element.querySelector('[data-testid="edit-title"]') as HTMLInputElement).value).toBe(
      'E-47 Druckabfall',
    );
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

    expect((element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).disabled).toBe(
      true,
    );

    type(element, 'edit-comment', 'Drehmoment korrigiert');
    await fixture.whenStable();

    expect((element.querySelector('[data-testid="edit-save"]') as HTMLButtonElement).disabled).toBe(
      false,
    );
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
    request.flush(
      { id: 'p-1', status: 'RECEIVED', message: 'ok' },
      { status: 202, statusText: 'Accepted' },
    );

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
    httpMock
      .expectOne('/api/moderation/protocols/p-1')
      .flush(
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
    httpMock
      .expectOne('/api/moderation/protocols/p-1')
      .flush(
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

  it('shows the approval STATE of every row, and nothing else in that column', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // On EVERY row, not only on the queue: this list is also where an administrator checks that
    // something they approved is still approved, and after a correction it will not be.
    const cell = element.querySelector('[data-testid="approval-state"]')?.textContent ?? '';
    expect(cell).toContain('Freigegeben');

    // AND NO ACTOR AND NO DATE, which is the fix for a defect Carlos found in production on
    // 2026-08-14: the column carried "Freigegeben system:corpus-seed · 12.08.2026" inside an
    // 11rem cap while the badge sets `white-space: nowrap`, so the text did not wrap — it
    // overflowed and ran under the action buttons. Who and when are provenance and moved into the
    // protocol viewer's history section, where they are read once beside the document.
    expect(cell).not.toContain('admin');
    expect(cell).not.toContain('11.08.2026');
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

  it('approves without a dialog when nothing on the machine is similar', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // THE ORDINARY CASE STAYS ONE CLICK. Duplicate detection asks first and then gets out of the
    // way — a dialog that opened every time to announce "nothing found" would tax the common case
    // to serve the rare one, and a permanently empty section teaches a reader to click past a
    // section that will one day be full.
    noSimilar();
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="duplicates-dialog"]')).toBeNull();

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

  it('reports a refused approval beside the row it belongs to', async () => {
    // THE FOUR-EYES CASE THAT USED TO BE HERE IS GONE with the runtime check behind it
    // (2026-08-15): the property is carried by the role split now, and no response this client can
    // receive says FOUR_EYES_REQUIRED any more. What remains worth asserting is the PLACEMENT — a
    // failure names the row it is about, because the reader pressed one button among ten identical
    // ones and a sentence at the top of the page about "this protocol" names none of them.
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    noSimilar();
    await fixture.whenStable();
    httpMock
      .expectOne('/api/moderation/protocols/p-1/approval')
      .flush(
        { reason: 'PROTOCOL_ARCHIVED', error: 'this protocol is in the archive' },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="approval-failure"]')?.textContent).toContain(
      'entfernt',
    );
    expect(element.querySelector('[data-testid="approval-failure-row"]')).not.toBeNull();
  });

  // -------------------------------------------------------------------------------------------
  // Duplicate detection — INFORMATION, never a gate
  // -------------------------------------------------------------------------------------------

  it('shows similar protocols before an approval, with each candidate’s own approval state', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    similarTo();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="duplicates-dialog"]')).not.toBeNull();
    // Which protocol is being approved, named: the button that opened this was one of several
    // identical ones in a table.
    expect(element.querySelector('[data-testid="duplicates-target"]')?.textContent).toContain(
      'E-47 Druckabfall',
    );

    const card = element.querySelector('[data-testid="duplicate-card"]');
    expect(card?.textContent).toContain('E-47 Druckabfall im Presshub');
    // A whole percentage. 93.05 beside 92.87 invites a reviewer to treat the difference as
    // meaningful, and the decision it informs — open both and read them — is the same either way.
    expect(element.querySelector('[data-testid="duplicate-score"]')?.textContent).toContain('93 %');
    // THE FIELD THIS FEATURE TURNS ON. "Nearly the same as something an administrator already
    // vouched for" is a merge-or-reject question; "nearly the same as one nobody has reviewed" may
    // be two people describing one fault from different angles, which this corpus wants both of.
    expect(card?.textContent).toContain('Freigegeben');
    // The threshold comes from the backend, so no screen hard-codes a configurable number.
    expect(element.querySelector('[data-testid="duplicates-method"]')?.textContent).toContain('92');

    // NOTHING WAS APPROVED YET, and nothing was refused either.
    httpMock.expectNone('/api/moderation/protocols/p-1/approval');
  });

  it('keeps the approve button enabled throughout — similarity warns, it never blocks', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    // A verbatim copy is the strongest signal this feature can produce.
    similarTo('p-1', { similarity: 0.9778 });
    await fixture.whenStable();

    // THE GOVERNING RULE, AS A TEST. There is no state of this dialog in which the button is
    // disabled, and no branch on the server that refuses an approval on a score either. Four
    // legitimate E-47 protocols describe four different root causes behind one fault code; a
    // feature that could turn "similar" into "refused" would have removed exactly the knowledge
    // that makes the demo answer good.
    const approve = element.querySelector(
      '[data-testid="duplicates-approve"]',
    ) as HTMLButtonElement;
    expect(approve.disabled).toBe(false);
    // And no danger styling: this is the review palette, and the words never say "warning".
    expect(approve.className).not.toContain('btn-danger');
    expect(element.querySelector('[data-testid="duplicates-intro"]')?.className).toContain(
      'notice-review',
    );

    approve.click();
    await fixture.whenStable();

    const request = httpMock.expectOne('/api/moderation/protocols/p-1/approval');
    expect(request.request.body).toEqual({ approved: true, comment: '' });
    request.flush({ state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-14T10:00:00Z' });
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="duplicates-dialog"]')).toBeNull();
  });

  it('renders no candidate section at all when nothing is similar', async () => {
    // THE EMPTY CASE. Not an empty list inside a dialog nobody needed — no dialog. A section that
    // is permanently blank is one readers learn to skip, including on the day it fills up.
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    noSimilar();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="duplicates-dialog"]')).toBeNull();
    expect(element.querySelector('[data-testid="duplicate-list"]')).toBeNull();
    expect(element.querySelector('[data-testid="duplicates-intro"]')).toBeNull();

    httpMock
      .expectOne('/api/moderation/protocols/p-1/approval')
      .flush({ state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-14T10:00:00Z' });
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();
  });

  it('counts the tail rather than dropping it', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    similarTo('p-1', {}, { total: 4, allIds: ['p-9', 'p-8', 'p-7', 'p-6'] });
    await fixture.whenStable();

    // Three links are a prompt to compare; ten are a report nobody reads. The count is what stops
    // the fourth from disappearing without trace.
    expect(element.querySelector('[data-testid="duplicates-more"]')?.textContent).toContain('3');

    (element.querySelector('[data-testid="duplicates-cancel"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    httpMock.expectNone('/api/moderation/protocols/p-1/approval');
  });

  it('opens a candidate in the read-only viewer, and comes back to the list', async () => {
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    similarTo();
    await fixture.whenStable();

    (element.querySelector('[data-testid="duplicate-open"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // A percentage is not a comparison: deciding that two accounts of one fault are one account
    // filed twice means reading both. The admin path, not the citation one — an admin holds no
    // shop-floor role and the citation endpoint would 403 for exactly this reader.
    httpMock
      .expectOne('/api/moderation/protocols/p-9/document')
      .flush(new Blob(['Symptom:\nKein Druck.\n'], { type: 'text/plain' }));
    noHistory('p-9');
    await fixture.whenStable();
    await fixture.whenStable();

    // ONE MODAL AT A TIME. Two focus traps in one page fight over Tab and Escape, so the duplicate
    // list steps aside rather than stacking — and it comes back with its state intact rather than
    // making the reviewer start the comparison again.
    expect(element.querySelector('[data-testid="duplicates-dialog"]')).toBeNull();

    (element.querySelector('[data-testid="protocol-close"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="duplicates-dialog"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="duplicate-card"]')?.textContent).toContain(
      'E-47 Druckabfall im Presshub',
    );

    (element.querySelector('[data-testid="duplicates-cancel"]') as HTMLButtonElement).click();
    await fixture.whenStable();
  });

  it('approves anyway when the similarity check itself fails', async () => {
    // FAIL OPEN, DELIBERATELY. Nothing is lost by it: the backend recomputes the same comparison
    // inside the approval and writes what it found into the ledger, so the audit record is correct
    // whether or not this call succeeded. Blocking an approval because a WARNING could not be
    // fetched would invert the rule the whole feature rests on.
    const fixture = await render(page([UNAPPROVED]));
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-approve"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    httpMock
      .expectOne('/api/moderation/protocols/p-1/similar')
      .flush('', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    httpMock
      .expectOne('/api/moderation/protocols/p-1/approval')
      .flush({ state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-14T10:00:00Z' });
    httpMock.expectOne((r) => r.url === '/api/moderation/protocols').flush(page([protocol()]));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="approval-notice"]')).not.toBeNull();
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
    noHistory('p-1');
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
    // Since 2026-08-14 this role can actually REACH this view — until then the route was
    // admin-only, the correction path had no interface for anybody, and this test was holding a
    // button nobody could press.
    roles.set(['schichtleiter']);
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="row-edit"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="row-approve"]')).toBeNull();
    expect(element.querySelector('[data-testid="row-withdraw"]')).toBeNull();
  });

  it('gives the Schichtleiter the correction job and nothing else', async () => {
    // THE FENCE, ON THE SCREEN. 2026-08-14 opened this route and two endpoints so that one write
    // could be reached; a corrector who finds Löschen or the archive here would have been handed
    // the administrator's job by a routing change, which is a worse defect than the one it fixed.
    //
    // ABSENT, NOT DISABLED, and asserted that way on purpose: a destructive control that exists
    // only to refuse is noise on a shop-floor tablet and a lie about the role.
    roles.set(['schichtleiter']);
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="row-delete"]')).toBeNull();
    // The whole tab strip, not just its second tab: one tab is a choice that is not a choice.
    expect(element.querySelector('[data-testid="tab-archive"]')).toBeNull();
    expect(element.querySelector('[data-testid="tab-corpus"]')).toBeNull();
    // What they DO keep: the list, and the two things the correction needs.
    expect(element.querySelector('[data-testid="moderation-table"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="row-open"]')).not.toBeNull();
  });

  it('names the screen after the job the reader may do on it', async () => {
    // Two roles, one route, two headings. "Protokollverwaltung" describes reviewing and removing;
    // a Schichtleiter does neither, and a title that claimed otherwise would contradict the buttons
    // directly underneath it.
    const admin = await render();
    expect(
      (admin.nativeElement as HTMLElement).querySelector('.page-title')?.textContent,
    ).toContain('Protokollverwaltung');

    roles.set(['schichtleiter']);
    const corrector = await render();
    const element = corrector.nativeElement as HTMLElement;
    expect(element.querySelector('.page-title')?.textContent).toContain('korrigieren');
    expect(element.querySelector('.page-lead')?.textContent).toContain('korrigieren');
  });

  it('warns in the correction dialog when the protocol is currently approved', async () => {
    // An edit resets approval unconditionally (#53), so a correction is how an approved protocol
    // quietly stops being approved. The person about to cause that reads it BEFORE they save —
    // one sentence in the dialog already open, not a second confirmation.
    const fixture = await render();
    const element = await openEdit(fixture);

    expect(element.querySelector('[data-testid="edit-resets-approval"]')?.textContent).toContain(
      'verliert es die Freigabe',
    );
  });

  it('says nothing about approval when the protocol has none to lose', async () => {
    // The ordinary case for a corrector — most of what reaches them is unreviewed — and it stays
    // silent. A line on every correction is a line readers stop seeing.
    const fixture = await render(page([UNAPPROVED]));
    const element = await openEdit(fixture);

    expect(element.querySelector('[data-testid="edit-resets-approval"]')).toBeNull();
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
    // The archive viewer asks for a history too, and it is the one place where the answer is
    // certain to be interesting: an archived protocol has at least a DELETE row, which is the
    // record ADR-006's archive exists to preserve.
    noHistory('a-1');
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
