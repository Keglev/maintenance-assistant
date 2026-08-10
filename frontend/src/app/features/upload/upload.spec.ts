import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { UploadStatus } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { Upload, typedProtocolFilename } from './upload';

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
      title: { set: (value: string) => void };
    };
    component.file.set(new File(['Symptom: etwas'], 'protokoll.txt', { type: 'text/plain' }));
    component.machineNo.set('PR-03');
    component.title.set('E-47 Druckabfall');
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

  // -------------------------------------------------------------------------------------
  // Typing a protocol instead of picking a file
  // -------------------------------------------------------------------------------------

  describe('text entry', () => {
    /** Types into the textarea after switching to text mode, the way a user reaches it. */
    async function type(fixture: Awaited<ReturnType<typeof render>>, value: string) {
      const element = fixture.nativeElement as HTMLElement;
      (element.querySelector('[data-testid="mode-text"]') as HTMLButtonElement).click();
      await fixture.whenStable();

      const textarea = element.querySelector('[data-testid="text-input"]') as HTMLTextAreaElement;
      textarea.value = value;
      textarea.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      return element;
    }

    function submitButton(element: HTMLElement) {
      return element.querySelector('[data-testid="upload-button"]') as HTMLButtonElement;
    }

    /** The metadata both modes require: a machine and, since this PR, a title. */
    function fillMeta(fixture: Awaited<ReturnType<typeof render>>, title = 'E-47 Druckabfall') {
      const component = fixture.componentInstance as unknown as {
        machineNo: { set: (value: string) => void };
        title: { set: (value: string) => void };
      };
      component.machineNo.set('PR-03');
      component.title.set(title);
    }

    it('offers the file mode by default, so nothing changes for anyone used to it', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;

      expect(element.querySelector('[data-testid="mode-file"]')?.getAttribute('aria-pressed')).toBe(
        'true',
      );
      expect(element.querySelector('[data-testid="file-input"]')).not.toBeNull();
      expect(element.querySelector('[data-testid="text-input"]')).toBeNull();
    });

    /**
     * The modes differ in ONE thing: where the content comes from. Everything else is metadata that
     * applies to both, and a field visible in one mode and not the other is a form that submits
     * what the user cannot see.
     */
    it('shows the same metadata fields in both modes', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;
      const metadata = ['upload-machine', 'upload-title', 'upload-error-code'];

      for (const id of metadata) {
        expect(element.querySelector(`[data-testid="${id}"]`), `${id} in file mode`).not.toBeNull();
      }
      expect(element.querySelector('[data-testid="file-input"]')).not.toBeNull();

      (element.querySelector('[data-testid="mode-text"]') as HTMLButtonElement).click();
      await fixture.whenStable();

      for (const id of metadata) {
        expect(element.querySelector(`[data-testid="${id}"]`), `${id} in text mode`).not.toBeNull();
      }
      // The only difference, in either direction.
      expect(element.querySelector('[data-testid="text-input"]')).not.toBeNull();
      expect(element.querySelector('[data-testid="file-input"]')).toBeNull();
    });

    it('offers no document-language field in either mode', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;

      for (const mode of ['mode-file', 'mode-text']) {
        (element.querySelector(`[data-testid="${mode}"]`) as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(element.querySelector('select[name="language"]')).toBeNull();
      }
    });

    it('requires a title in both modes, marked as required', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;
      const title = element.querySelector('[data-testid="upload-title"]') as HTMLInputElement;

      // Programmatically as well as visually: a red asterisk nobody can hear is not a label.
      expect(title.getAttribute('aria-required')).toBe('true');
      expect(title.required).toBe(true);

      // Text mode: content and a machine, but no title.
      fillMeta(fixture, '   ');
      const withText = await type(fixture, 'Presse kommt nicht auf Druck.');
      expect(submitButton(withText).disabled).toBe(true);

      fillMeta(fixture);
      await fixture.whenStable();
      expect(submitButton(withText).disabled).toBe(false);
    });

    it('requires a title in file mode too', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;
      (
        fixture.componentInstance as unknown as {
          file: { set: (value: File) => void };
          machineNo: { set: (value: string) => void };
        }
      ).file.set(new File(['x'], 'p.txt', { type: 'text/plain' }));
      fillMeta(fixture, '');
      await fixture.whenStable();

      // A protocol with no title reduces "Meine Uploads" to a machine number and a date.
      expect(submitButton(element).disabled).toBe(true);
    });

    it('refuses to submit with neither a file nor any text', async () => {
      const fixture = await render();
      fillMeta(fixture);
      await fixture.whenStable();

      expect(submitButton(fixture.nativeElement as HTMLElement).disabled).toBe(true);
    });

    it('refuses to submit text that is only whitespace', async () => {
      const fixture = await render();
      fillMeta(fixture);
      const element = await type(fixture, '   \n\t  \n ');

      // A protocol of three spaces is an empty protocol that would still reach the embedder.
      expect(submitButton(element).disabled).toBe(true);
    });

    it('submits on typed text alone, with no file anywhere', async () => {
      const fixture = await render();
      fillMeta(fixture);
      const element = await type(fixture, 'Presse kommt nicht auf Druck.');

      expect(submitButton(element).disabled).toBe(false);
    });

    it('submits on a file alone, as it always did', async () => {
      const fixture = await render();
      fillMeta(fixture);
      (
        fixture.componentInstance as unknown as { file: { set: (value: File) => void } }
      ).file.set(new File(['x'], 'p.txt', { type: 'text/plain' }));
      await fixture.whenStable();

      expect(submitButton(fixture.nativeElement as HTMLElement).disabled).toBe(false);
    });

    it('clears the other mode when the mode is switched', async () => {
      const fixture = await render();
      fillMeta(fixture);
      const element = await type(fixture, 'Etwas getippt.');
      expect(submitButton(element).disabled).toBe(false);

      (element.querySelector('[data-testid="mode-file"]') as HTMLButtonElement).click();
      await fixture.whenStable();

      // A stale input the user can no longer see would be submitted as a surprise.
      expect(submitButton(element).disabled).toBe(true);

      (element.querySelector('[data-testid="mode-text"]') as HTMLButtonElement).click();
      await fixture.whenStable();
      expect(
        (element.querySelector('[data-testid="text-input"]') as HTMLTextAreaElement).value,
      ).toBe('');
    });

    /**
     * THE ASSERTION THIS FEATURE RESTS ON.
     *
     * The whole reason no backend change was needed is that a typed protocol arrives as the same
     * multipart file an uploaded one does. If the File were empty, misnamed or the wrong type, the
     * pipeline would take it anyway and fail somewhere far from here — so the bytes are read back
     * out of the request rather than trusted.
     */
    it('sends the typed text as a text/plain File through the unchanged upload call', async () => {
      const written = 'Symptom:\nKein Druck.\n\nMassnahme:\nDichtsatz getauscht.';
      const fixture = await render();
      fillMeta(fixture);
      const element = await type(fixture, `  ${written}  `);

      submitButton(element).click();
      const request = httpMock.expectOne('/api/protocols');
      const body = request.request.body as FormData;
      const file = body.get('file') as File;

      expect(file).toBeInstanceOf(File);
      expect(file.type).toBe('text/plain');
      // Trimmed: the leading spaces are an artefact of typing, not part of the protocol.
      expect(await file.text()).toBe(written);
      // The metadata fields apply to both modes and are unchanged by this feature.
      expect(body.get('machine')).toBe('PR-03');
      expect(body.get('type')).toBe('STOERUNG');
      expect(body.get('title')).toBe('E-47 Druckabfall');
      // No language part at all. Retrieval is language-agnostic by architecture and the answer is
      // pinned to the QUESTION's language, so the field asked the Schichtleiter a question whose
      // answer nothing read (DECISIONS.txt, 2026-08-10).
      expect(body.get('language')).toBeNull();

      request.flush({ id: 'p-9', status: 'RECEIVED', message: 'queued' }, { status: 202, statusText: 'Accepted' });
      httpMock.expectOne('/api/protocols/mine').flush(UPLOADS);
      await fixture.whenStable();
    });

    it('names a typed protocol after its machine and the moment it was written', async () => {
      const fixture = await render();
      fillMeta(fixture);
      const element = await type(fixture, 'Etwas.');

      submitButton(element).click();
      const request = httpMock.expectOne('/api/protocols');
      const file = (request.request.body as FormData).get('file') as File;

      // The name reaches the volume and comes back in Content-Disposition when the viewer offers
      // the download, so it has to be readable and ASCII.
      expect(file.name).toMatch(/^PR-03-\d{8}-\d{6}-eingabe\.txt$/);

      request.flush({ id: 'p-9', status: 'RECEIVED', message: 'queued' }, { status: 202, statusText: 'Accepted' });
      httpMock.expectOne('/api/protocols/mine').flush(UPLOADS);
      await fixture.whenStable();
    });

    it('accepts a typed protocol exactly as it accepts an uploaded one', async () => {
      const fixture = await render();
      fillMeta(fixture);
      const element = await type(fixture, 'Presse kommt nicht auf Druck.');

      submitButton(element).click();
      httpMock
        .expectOne('/api/protocols')
        .flush({ id: 'p-9', status: 'RECEIVED', message: 'queued' }, { status: 202, statusText: 'Accepted' });
      // The same refresh as after a file upload — the call is identical, so the aftermath is too.
      httpMock.expectOne('/api/protocols/mine').flush(UPLOADS);
      await fixture.whenStable();

      expect(element.querySelector('[data-testid="accepted"]')?.textContent).toContain(
        'angenommen',
      );
      // The textarea is emptied, so the next protocol does not start with the previous one in it.
      expect(
        (element.querySelector('[data-testid="text-input"]') as HTMLTextAreaElement).value,
      ).toBe('');
      expect(submitButton(element).disabled).toBe(true);
    });
  });

  describe('server-side guards, as the user reads them', () => {
    /** Submits a valid-looking upload and fails it with the given status and body. */
    async function refuse(status: number, body: Record<string, string>) {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;
      const component = fixture.componentInstance as unknown as {
        file: { set: (value: File) => void };
        machineNo: { set: (value: string) => void };
        title: { set: (value: string) => void };
      };
      component.file.set(new File(['x'], 'p.txt', { type: 'text/plain' }));
      component.machineNo.set('PR-03');
      component.title.set('E-47');
      await fixture.whenStable();

      (element.querySelector('[data-testid="upload-button"]') as HTMLButtonElement).click();
      httpMock.expectOne('/api/protocols').flush(body, { status, statusText: 'refused' });
      await fixture.whenStable();
      return element.querySelector('[data-testid="upload-failure"]')?.textContent ?? '';
    }

    it('explains a 413 as a file to shorten, not as a failure', async () => {
      const text = await refuse(413, { reason: 'FILE_TOO_LARGE', limit: '256KB' });

      expect(text).toContain('zu groß');
    });

    it('explains a refused file as the wrong kind of file', async () => {
      // All three backend codes mean the same thing to the person at the keyboard: this file is
      // not something the system can read.
      for (const reason of ['EMPTY_FILE', 'UNSUPPORTED_TYPE', 'NOT_TEXT']) {
        const text = await refuse(400, { reason, error: 'nope' });
        expect(text, reason).toContain('nicht gelesen werden');
      }
    });

    it('leaves an ordinary 400 generic, because it is a different kind of mistake', async () => {
      // A field-level error the form should have prevented is not "your file is unreadable".
      const text = await refuse(400, { error: 'unknown machine: XX-99' });

      expect(text).toContain('fehlgeschlagen');
    });

    it('explains an upload rate limit as something to wait out', async () => {
      const text = await refuse(429, { reason: 'RATE_LIMITED', error: 'slow down' });

      expect(text).toContain('Zu viele Uploads');
    });
  });

  it('reports a refused upload as a permission problem', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const component = fixture.componentInstance as unknown as {
      file: { set: (value: File) => void };
      machineNo: { set: (value: string) => void };
      title: { set: (value: string) => void };
    };
    component.file.set(new File(['x'], 'p.txt', { type: 'text/plain' }));
    component.machineNo.set('PR-03');
    component.title.set('E-47 Druckabfall');
    await fixture.whenStable();

    (element.querySelector('[data-testid="upload-button"]') as HTMLButtonElement).click();
    httpMock.expectOne('/api/protocols').flush({}, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="upload-failure"]')?.textContent).toContain(
      'Berechtigung',
    );
  });
});

describe('typedProtocolFilename', () => {
  it('names the file after the machine and the local wall clock, to the second', () => {
    // Local rather than UTC: the name is read by the person who typed it, and 22:14 means their
    // shift. Two protocols in one shift are told apart by the seconds.
    const at = new Date(2026, 7, 10, 22, 14, 5);

    expect(typedProtocolFilename('PR-03', at)).toBe('PR-03-20260810-221405-eingabe.txt');
  });

  it('keeps the name ASCII, whatever a future machine number looks like', () => {
    // The name travels to the volume and comes back in Content-Disposition; every layer between
    // has its own opinion about non-ASCII filenames.
    expect(typedProtocolFilename('Förderband/04', new Date(2026, 0, 2, 3, 4, 5))).toBe(
      'F-rderband-04-20260102-030405-eingabe.txt',
    );
  });

  it('still produces a usable name when the machine number is unusable', () => {
    expect(typedProtocolFilename('///', new Date(2026, 0, 2, 3, 4, 5))).toBe(
      'protokoll-20260102-030405-eingabe.txt',
    );
  });
});
