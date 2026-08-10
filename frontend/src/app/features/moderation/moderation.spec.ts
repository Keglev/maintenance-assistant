import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ModeratedProtocol, ProtocolPage } from '../../core/api/api.types';
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
      ...overrides,
    };
  }

  function page(items: ModeratedProtocol[], total = items.length, index = 0): ProtocolPage {
    return { items, page: index, size: 10, total };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Moderation],
      providers: [provideHttpClient(), provideHttpClientTesting()],
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
      | HTMLSelectElement;
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
    expect(request.request.params.get('size')).toBe('10');
    // An empty field is a field nobody filled in, so it is left off the query string rather than
    // sent as "" — the backend would have to interpret that instead of reading it.
    expect(request.request.params.has('machineNo')).toBe(false);
    expect(request.request.params.has('titleContains')).toBe(false);
    request.flush(page([protocol()]));
    httpMock.expectOne('/api/machines').flush([]);
    await fixture.whenStable();
  });

  it('says plainly that protocols are not edited, only replaced', async () => {
    const fixture = await render();

    // The absence of an edit button is a decision (ADR-006), and an unexplained absence reads as
    // an oversight.
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="moderation-hint"]')
        ?.textContent,
    ).toContain('neu hochladen');
  });

  it('names the protocol in the delete confirmation, and deletes nothing until confirmed', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    const target = element.querySelector('[data-testid="delete-target"]')?.textContent ?? '';
    expect(target).toContain('E-47 Druckabfall');
    expect(target).toContain('PR-03');
    expect(element.querySelector('[data-testid="delete-confirm-dialog"]')?.textContent).toContain(
      'Endgültig',
    );
    // httpMock.verify() in afterEach proves no DELETE was sent by opening the dialog.
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
    (element.querySelector('[data-testid="delete-confirm-button"]') as HTMLButtonElement).click();

    const request = httpMock.expectOne('/api/moderation/protocols/p-1');
    expect(request.request.method).toBe('DELETE');
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

  it('reports a protocol someone else already deleted, rather than failing generically', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('[data-testid="row-delete"]') as HTMLButtonElement).click();
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
