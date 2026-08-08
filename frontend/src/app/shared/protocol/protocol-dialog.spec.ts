import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nService } from '../../core/i18n/i18n.service';
import { ProtocolDialog } from './protocol-dialog';

const PROTOCOL_ID = '0f9c5b02-0000-4000-8000-000000000001';
const DOCUMENT_URL = `/api/protocols/${PROTOCOL_ID}/document`;

const WELL_FORMED = [
  'WARTUNGSPROTOKOLL',
  '=================',
  '',
  'Maschine: PR-03',
  'Fehlercode: E-47',
  '',
  'E-47 Druckabfall im Presshub',
  '',
  'Symptom:',
  'Presse kommt nicht auf Druck.',
  '',
  'Massnahme:',
  'Dichtsatz erneuert.',
].join('\n');

/** No banner, no header, no labelled section — the shape the parser is entitled to give up on. */
const MESSY = 'kein druck an der presse, filter getauscht, laeuft wieder';

@Component({
  imports: [ProtocolDialog],
  template: `<button type="button" id="opener" (click)="id.set('${PROTOCOL_ID}')">open</button>
    <app-protocol-dialog
      [protocolId]="id()"
      protocolTitle="E-47 Druckabfall im Presshub"
      machine="PR-03 · Presse 3"
      (closed)="id.set(null)"
    />`,
})
class HostComponent {
  readonly id = signal<string | null>(null);
}

/**
 * The viewer replaces #26's blob tab.
 *
 * #26 was right that the document must be fetched through HttpClient — a browser-followed
 * navigation carries no Bearer token — and wrong that it should then leave the application. These
 * assertions are the behaviour that replaced it: the protocol renders here, parsed when it can be
 * and unchanged when it cannot, with the original file still one click away.
 */
describe('ProtocolDialog', () => {
  let httpMock: HttpTestingController;

  async function render(language: 'de' | 'en' = 'de') {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use(language);

    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    return fixture;
  }

  /** Opens the viewer through the trigger and answers the document request with `body`. */
  async function open(
    fixture: Awaited<ReturnType<typeof render>>,
    body: string | null,
    status?: number,
  ) {
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#opener') as HTMLButtonElement).click();
    await fixture.whenStable();

    const request = httpMock.expectOne(DOCUMENT_URL);
    if (body === null) {
      request.flush(new Blob(), { status: status ?? 500, statusText: 'error' });
    } else {
      request.flush(new Blob([body], { type: 'text/plain' }), {
        headers: { 'Content-Disposition': 'inline; filename="PR-03-E-47.txt"' },
      });
    }
    // The blob is decoded asynchronously, so the parsed body lands one microtask after the flush.
    await fixture.whenStable();
    await fixture.whenStable();
    return element;
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => httpMock.verify());

  it('renders nothing and requests nothing until a protocol is chosen', async () => {
    const fixture = await render();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="protocol-dialog"]'),
    ).toBeNull();
    // httpMock.verify() proves the document was not fetched for a dialog nobody opened.
  });

  it('renders the parsed protocol as a metadata grid and labelled sections', async () => {
    const fixture = await render();
    const element = await open(fixture, WELL_FORMED);

    const metaGrid = element.querySelector('[data-testid="protocol-meta"]') as HTMLElement;
    expect(metaGrid.textContent).toContain('Maschine');
    expect(metaGrid.textContent).toContain('PR-03');

    const sections = element.querySelectorAll('[data-testid="protocol-section"]');
    expect(sections.length).toBe(2);
    expect(sections[0].textContent).toContain('Symptom');
    expect(sections[0].textContent).toContain('Presse kommt nicht auf Druck.');

    // The banner is chrome and the dialog head already names the document.
    expect(element.querySelector('[data-testid="protocol-body"]')?.textContent).not.toContain(
      'WARTUNGSPROTOKOLL',
    );
    expect(element.querySelector('[data-testid="protocol-raw"]')).toBeNull();
  });

  it('falls back to the raw file, preformatted, when the structure is unrecognisable', async () => {
    const fixture = await render();
    const element = await open(fixture, MESSY);

    // Never blank and never a broken layout: the file itself is still the evidence behind the claim.
    expect(element.querySelector('[data-testid="protocol-raw"]')?.textContent).toContain(MESSY);
    expect(element.querySelector('[data-testid="protocol-fallback-note"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="protocol-meta"]')).toBeNull();
  });

  it('names the protocol and the machine in the head', async () => {
    const fixture = await render();
    const element = await open(fixture, WELL_FORMED);

    expect(element.querySelector('[data-testid="protocol-dialog"]')?.textContent).toContain(
      'E-47 Druckabfall im Presshub',
    );
    expect(element.querySelector('[data-testid="protocol-machine"]')?.textContent).toContain(
      'PR-03 · Presse 3',
    );
  });

  it('shows a loading state while the document is on its way', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    (element.querySelector('#opener') as HTMLButtonElement).click();
    await fixture.whenStable();

    // The dialog opens on the click rather than when the bytes arrive: on a tablet the round trip
    // is visible, and a button that does nothing for a second reads as a broken button.
    expect(element.querySelector('[data-testid="protocol-dialog"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="protocol-loading"]')).not.toBeNull();

    httpMock.expectOne(DOCUMENT_URL).flush(new Blob([WELL_FORMED], { type: 'text/plain' }));
    await fixture.whenStable();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="protocol-loading"]')).toBeNull();
  });

  it('offers the original file for download, with the name the backend gave it', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    const fixture = await render();
    const element = await open(fixture, WELL_FORMED);

    const download = element.querySelector('[data-testid="protocol-download"]') as HTMLButtonElement;
    expect(download.disabled).toBe(false);
    download.click();
    await fixture.whenStable();

    // The only remaining object URL in the application, and the one thing an object URL is for.
    expect(createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();

    createObjectURL.mockRestore();
    click.mockRestore();
  });

  it('says so when the original protocol can no longer be opened', async () => {
    const fixture = await render();
    const element = await open(fixture, null, 404);

    // Clicking into silence is the worst outcome: the claim in the answer still rests on this
    // protocol, and the reader is entitled to know the evidence is unreachable.
    expect(element.querySelector('[data-testid="protocol-failure"]')?.textContent).toContain(
      'lässt sich nicht mehr öffnen',
    );
    expect(
      (element.querySelector('[data-testid="protocol-download"]') as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('closes on Escape, on the close button and on the backdrop', async () => {
    const fixture = await render();
    const element = await open(fixture, WELL_FORMED);

    element
      .querySelector('[data-testid="protocol-backdrop"]')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="protocol-dialog"]')).toBeNull();

    await open(fixture, WELL_FORMED);
    (element.querySelector('[data-testid="protocol-close"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="protocol-dialog"]')).toBeNull();

    await open(fixture, WELL_FORMED);
    (element.querySelector('[data-testid="protocol-backdrop"]') as HTMLElement).click();
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="protocol-dialog"]')).toBeNull();
  });

  it('hands focus back to whatever opened it', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const opener = element.querySelector('#opener') as HTMLButtonElement;

    // Focused explicitly: a programmatic click does not move focus, and the trigger being where
    // focus came from is the whole premise of handing it back.
    opener.focus();
    opener.click();
    await fixture.whenStable();
    httpMock.expectOne(DOCUMENT_URL).flush(new Blob([WELL_FORMED], { type: 'text/plain' }));
    await fixture.whenStable();

    (element.querySelector('[data-testid="protocol-close"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // Otherwise a keyboard user is dropped at the top of the document after every source they read.
    expect(document.activeElement).toBe(opener);
  });

  it('translates its own labels with the interface language', async () => {
    const fixture = await render('en');
    const element = await open(fixture, WELL_FORMED);

    expect(element.querySelector('[data-testid="protocol-download"]')?.textContent).toContain(
      'Download',
    );
  });
});
