import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { UploadStatus } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { Upload } from './upload';

/**
 * The upload view exists to be honest about a 202: the protocol is stored and not yet searchable.
 * These tests assert that honesty survives — the status list, the failure reason, and the absence
 * of a polling loop.
 */
describe('Upload', () => {
  let httpMock: HttpTestingController;

  const MACHINES = [
    { id: 'machine-1', machineNo: 'PR-03', name: 'Presse 3', type: 'Presse', location: null },
  ];

  const UPLOADS: UploadStatus[] = [
    {
      id: 'p-1',
      machineNo: 'PR-03',
      title: 'E-47 Druckabfall',
      status: 'FAILED',
      failureReason: 'IllegalStateException: source file is not UTF-8 text',
      createdAt: '2026-08-07T10:15:00Z',
      indexedAt: null,
    },
    {
      id: 'p-2',
      machineNo: 'PR-03',
      title: 'Ölwechsel',
      status: 'INDEXED',
      failureReason: null,
      createdAt: '2026-08-06T09:00:00Z',
      indexedAt: '2026-08-06T09:00:20Z',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Upload],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use('de');
  });

  afterEach(() => httpMock.verify());

  async function render(uploads: UploadStatus[] = UPLOADS) {
    const fixture = TestBed.createComponent(Upload);
    httpMock.expectOne('/api/machines').flush(MACHINES);
    httpMock.expectOne('/api/protocols/mine').flush(uploads);
    await fixture.whenStable();
    return fixture;
  }

  it('lists own uploads with their status', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="uploads-table"]')?.textContent).toContain(
      'Durchsuchbar',
    );
    expect(element.querySelector('[data-testid="uploads-table"]')?.textContent).toContain(
      'Fehlgeschlagen',
    );
  });

  it('shows why an upload failed, not only that it did', async () => {
    const fixture = await render();

    // "It failed" with no reason makes re-uploading the same broken file the obvious next move.
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="failure-reason"]')
        ?.textContent,
    ).toContain('not UTF-8');
  });

  it('says an upload is accepted rather than finished', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const component = fixture.componentInstance as unknown as {
      file: { set: (value: File) => void };
      machineNo: { set: (value: string) => void };
    };
    component.file.set(new File(['Symptom: etwas'], 'protokoll.txt', { type: 'text/plain' }));
    component.machineNo.set('PR-03');
    await fixture.whenStable();

    (element.querySelector('[data-testid="upload-button"]') as HTMLButtonElement).click();
    httpMock
      .expectOne('/api/protocols')
      .flush({ id: 'p-3', status: 'RECEIVED', message: 'queued' }, { status: 202, statusText: 'Accepted' });
    // The view refreshes the list once after a successful upload, so the new row is visible in the
    // state it is actually in.
    httpMock.expectOne('/api/protocols/mine').flush(UPLOADS);
    await fixture.whenStable();

    const accepted = element.querySelector('[data-testid="accepted"]')?.textContent ?? '';
    expect(accepted).toContain('angenommen');
    expect(accepted).toContain('noch nicht durchsuchbar');
  });

  it('refreshes the status only when asked, never on a timer', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // Nothing must be in flight until the button is pressed: a poll would run for as long as the
    // tab is open, to answer "still the same".
    httpMock.verify();

    (element.querySelector('[data-testid="refresh-button"]') as HTMLButtonElement).click();
    httpMock.expectOne('/api/protocols/mine').flush(UPLOADS);
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="uploads-table"]')).not.toBeNull();
  });

  it('says so plainly when there is nothing uploaded yet', async () => {
    const fixture = await render([]);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="no-uploads"]'),
    ).not.toBeNull();
  });

  it('reports a refused upload as a permission problem', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const component = fixture.componentInstance as unknown as {
      file: { set: (value: File) => void };
      machineNo: { set: (value: string) => void };
    };
    component.file.set(new File(['x'], 'p.txt', { type: 'text/plain' }));
    component.machineNo.set('PR-03');
    await fixture.whenStable();

    (element.querySelector('[data-testid="upload-button"]') as HTMLButtonElement).click();
    httpMock.expectOne('/api/protocols').flush({}, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="upload-failure"]')?.textContent).toContain(
      'Berechtigung',
    );
  });
});
