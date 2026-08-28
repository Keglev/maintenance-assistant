import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { UploadStatus } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { Upload } from './upload';

/**
 * The upload form driven through its controls rather than through its signals.
 *
 * <p>The contract under test: what the user puts into the form is what leaves the browser, and the
 * list underneath it stays navigable. These are the template's own listeners — the select and input
 * bindings, the file picker and the pager — which a test that assigns to a signal never runs, and
 * which the coverage survey found uncovered as a block.
 *
 * <p>OUT OF SCOPE: everything upload.spec.ts covers — mode switching, validation, the 202, the
 * typed-protocol filename, and the server-side guards as the user reads them.
 *
 * <p>SIBLING: upload.spec.ts.
 */
describe('Upload — driven through the form', () => {
  let http: HttpTestingController;

  const MACHINES = [
    { id: 'machine-1', machineNo: 'PR-03', name: 'Presse 3', type: 'Presse', location: null },
    { id: 'machine-2', machineNo: 'AB-02', name: 'Abfüller 2', type: 'Abfüller', location: null },
  ];

  /** Six, so the five-per-page list needs a second page and the pager has something to do. */
  const SIX_UPLOADS: UploadStatus[] = Array.from({ length: 6 }, (_, index) => ({
    id: `p-${index + 1}`,
    machineNo: 'PR-03',
    title: `Protokoll ${index + 1}`,
    status: 'INDEXED' as const,
    failureReason: null,
    createdAt: `2026-08-0${index + 1}T09:00:00Z`,
    indexedAt: `2026-08-0${index + 1}T09:00:20Z`,
  }));

  beforeEach(async () => {
    // Entry clear: I18nService reads a stored language on construction, and this file pins German.
    localStorage.clear();
    TestBed.resetTestingModule();

    await TestBed.configureTestingModule({
      imports: [Upload],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use('de');
  });

  afterEach(() => {
    http.verify();
    // Exit clear: the only cleanup the next file inherits.
    localStorage.clear();
  });

  async function render(uploads: UploadStatus[] = []): Promise<ComponentFixture<Upload>> {
    const fixture = TestBed.createComponent(Upload);
    http.expectOne('/api/machines').flush(MACHINES);
    http.expectOne('/api/protocols/mine').flush(uploads);
    await fixture.whenStable();
    return fixture;
  }

  function query(fixture: ComponentFixture<Upload>, selector: string): HTMLElement {
    const element = (fixture.nativeElement as HTMLElement).querySelector(selector);
    if (!element) {
      throw new Error(`No element matching ${selector}`);
    }
    return element as HTMLElement;
  }

  const byId = (fixture: ComponentFixture<Upload>, testId: string) =>
    query(fixture, `[data-testid="${testId}"]`);

  async function type(
    fixture: ComponentFixture<Upload>,
    selector: string,
    value: string,
  ): Promise<void> {
    const field = query(fixture, selector) as HTMLInputElement | HTMLSelectElement;
    field.value = value;
    field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'));
    await fixture.whenStable();
  }

  it('sends what was chosen and typed in the form, not what the component started with', async () => {
    const fixture = await render();

    await type(fixture, '[data-testid="upload-machine"]', 'AB-02');
    await type(fixture, 'select[name="type"]', 'WARTUNG');
    await type(fixture, '[data-testid="upload-title"]', 'Halbjahreswartung');
    await type(fixture, '[data-testid="upload-error-code"]', 'W-12');

    (byId(fixture, 'mode-text') as HTMLButtonElement).click();
    await fixture.whenStable();
    await type(fixture, '[data-testid="text-input"]', 'Sichtprüfung ohne Befund.');

    (byId(fixture, 'upload-button') as HTMLButtonElement).click();
    const request = http.expectOne('/api/protocols');
    const body = request.request.body as FormData;

    // Every one of these four came from a control the user operated. A form that read its own
    // defaults would file this protocol against PR-03 as a fault — the machine and the type it
    // happened to start on — and nothing on screen would have said so.
    // 'machine', not 'machineNo': the endpoint takes the plant identifier and resolves the row.
    expect(body.get('machine')).toBe('AB-02');
    expect(body.get('type')).toBe('WARTUNG');
    expect(body.get('title')).toBe('Halbjahreswartung');
    expect(body.get('errorCode')).toBe('W-12');

    request.flush({ id: 'p-9', status: 'RECEIVED' });
    http.expectOne('/api/protocols/mine').flush([]);
    await fixture.whenStable();
  });

  it('takes the file the browser hands it, and submits that file', async () => {
    const fixture = await render();

    await type(fixture, '[data-testid="upload-machine"]', 'PR-03');
    await type(fixture, '[data-testid="upload-title"]', 'E-47 Druckabfall');

    const chosen = new File(['Symptom:\nKein Druck.\n'], 'e47.txt', { type: 'text/plain' });
    const input = byId(fixture, 'file-input') as HTMLInputElement;
    // The browser sets `files`; a test can only stand it up. What is being exercised is the
    // component's change handler, which is the half that reads the property.
    Object.defineProperty(input, 'files', { value: [chosen], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect((byId(fixture, 'upload-button') as HTMLButtonElement).disabled).toBe(false);

    (byId(fixture, 'upload-button') as HTMLButtonElement).click();
    const request = http.expectOne('/api/protocols');
    const sent = (request.request.body as FormData).get('file') as File;

    // THE FILE ITSELF, by name: the form has two ways to produce one — a chosen file and a typed
    // protocol wrapped client-side — and this is the path where the user's own file must survive
    // rather than be replaced by a wrapper around an empty textarea.
    expect(sent.name).toBe('e47.txt');

    request.flush({ id: 'p-9', status: 'RECEIVED' });
    http.expectOne('/api/protocols/mine').flush([]);
    await fixture.whenStable();
  });

  it('pages back through the uploads without asking the server again', async () => {
    const fixture = await render(SIX_UPLOADS);

    expect(byId(fixture, 'uploads-table').textContent).toContain('Protokoll 1');

    (byId(fixture, 'uploads-next') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(byId(fixture, 'uploads-table').textContent).toContain('Protokoll 6');
    expect(byId(fixture, 'uploads-table').textContent).not.toContain('Protokoll 1');

    (byId(fixture, 'uploads-previous') as HTMLButtonElement).click();
    await fixture.whenStable();

    // Back on the first page, and no request went out — the list was fetched once and is paged in
    // the browser. verify() in the teardown is what enforces the second half of that sentence.
    expect(byId(fixture, 'uploads-table').textContent).toContain('Protokoll 1');
    expect((byId(fixture, 'uploads-previous') as HTMLButtonElement).disabled).toBe(true);
  });

  it('stops the refresh spinner and says so when the list cannot be read', async () => {
    const fixture = await render(SIX_UPLOADS);

    (byId(fixture, 'refresh-button') as HTMLButtonElement).click();
    http
      .expectOne('/api/protocols/mine')
      .flush('nope', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    // The button comes back and the reason is on screen. A refresh that failed silently would leave
    // the uploader reading a list that is as old as the page and looks current.
    expect((byId(fixture, 'refresh-button') as HTMLButtonElement).disabled).toBe(false);
    expect(byId(fixture, 'upload-failure').textContent?.trim()).not.toBe('');
    // What was already read stays on screen: a failed refresh is not a reason to blank the table.
    expect(byId(fixture, 'uploads-table').textContent).toContain('Protokoll 1');
  });
});
