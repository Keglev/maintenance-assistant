import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Approval, ModerationEvent, ProtocolHistory } from '../../core/api/api.types';
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
 * A host for the MODERATION paths, where the viewer also shows a history.
 *
 * Its own host rather than more inputs on the one above: the citation host's whole value is that it
 * is the shop floor's viewer with nothing added, and `httpMock.verify()` on it proves the history is
 * never even asked for there.
 */
@Component({
  imports: [ProtocolDialog],
  template: `<button type="button" id="opener" (click)="id.set('${PROTOCOL_ID}')">open</button>
    <app-protocol-dialog
      [protocolId]="id()"
      [approval]="approval()"
      [source]="source()"
      protocolTitle="E-47 Druckabfall im Presshub"
      machine="PR-03 · Presse 3"
      (closed)="id.set(null)"
    />`,
})
class HistoryHostComponent {
  readonly id = signal<string | null>(null);
  readonly approval = signal<Approval | null>(null);
  readonly source = signal<'moderation' | 'citation'>('moderation');
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

    const download = element.querySelector(
      '[data-testid="protocol-download"]',
    ) as HTMLButtonElement;
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

/**
 * The history section: what has been done to this protocol, and by whom.
 *
 * <p>Carlos's ruling of 2026-08-14 after drilling #56 in production: the records TABLE shows the
 * approval STATE and nothing else — the column carrying "Freigegeben system:corpus-seed ·
 * 12.08.2026" truncated under the action buttons — and WHO and WHEN move here, read once, beside
 * the document they describe.
 */
describe('ProtocolDialog — history', () => {
  let httpMock: HttpTestingController;

  const HISTORY_URL = `/api/moderation/protocols/${PROTOCOL_ID}/history`;
  const MODERATION_DOCUMENT_URL = `/api/moderation/protocols/${PROTOCOL_ID}/document`;

  function event(overrides: Partial<ModerationEvent> = {}): ModerationEvent {
    return {
      action: 'APPROVE',
      actor: 'admin',
      comment: 'geprüft und freigegeben',
      at: '2026-08-14T09:30:00Z',
      ...overrides,
    };
  }

  async function render(
    approval: Approval | null,
    source: 'moderation' | 'citation' = 'moderation',
  ) {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [HistoryHostComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use('de');

    const fixture = TestBed.createComponent(HistoryHostComponent);
    fixture.componentInstance.approval.set(approval);
    fixture.componentInstance.source.set(source);
    await fixture.whenStable();
    return fixture;
  }

  /** Opens the viewer and answers the document; `history` null means the call is not expected. */
  async function open(
    fixture: Awaited<ReturnType<typeof render>>,
    history: ProtocolHistory | null,
    documentUrl = MODERATION_DOCUMENT_URL,
  ) {
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#opener') as HTMLButtonElement).click();
    await fixture.whenStable();

    httpMock.expectOne(documentUrl).flush(new Blob([WELL_FORMED], { type: 'text/plain' }));
    if (history) {
      httpMock.expectOne(HISTORY_URL).flush(history);
    }
    await fixture.whenStable();
    await fixture.whenStable();
    return element;
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => httpMock.verify());

  it('lists the acts newest first, with the actor, the time and the reason', async () => {
    const fixture = await render({
      state: 'UNAPPROVED',
      approvedBy: null,
      approvedAt: null,
    });
    const element = await open(fixture, {
      events: [
        event({ action: 'UNAPPROVE', actor: 'admin', comment: 'Massnahme passt nicht' }),
        event({ action: 'EDIT', actor: 'schichtleiter', comment: 'Drehmoment korrigiert' }),
        event({ action: 'APPROVE', actor: 'admin', comment: 'geprüft' }),
      ],
      total: 3,
      limit: 3,
    });

    const items = element.querySelectorAll('[data-testid="history-item"]');
    expect(items.length).toBe(3);
    // The verb in plain language: `UNAPPROVE` is a database value and must never reach a screen.
    expect(items[0].textContent).toContain('Freigabe zurückgezogen');
    expect(items[1].textContent).toContain('Korrigiert');
    expect(items[0].querySelector('[data-testid="history-actor"]')?.textContent).toContain('admin');
    // Every row carries a reason by database constraint, so there is no empty case to render.
    expect(items[0].querySelector('[data-testid="history-comment"]')?.textContent).toContain(
      'Massnahme passt nicht',
    );
    // THE TIME AS WELL AS THE DAY, which is what the table format cannot say: two corrections on
    // one afternoon are a different story from two a month apart.
    const when = items[0].querySelector('[data-testid="history-when"]')?.textContent ?? '';
    expect(when).toContain('14.08.2026');
    expect(when).toMatch(/\d{2}:\d{2}/);
  });

  it('caps at three and says how many it is not showing', async () => {
    const fixture = await render(null);
    const element = await open(fixture, {
      events: [event(), event(), event()],
      total: 7,
      limit: 3,
    });

    expect(element.querySelectorAll('[data-testid="history-item"]').length).toBe(3);
    // ONE QUIET LINE, AND NO "SHOW ALL". The cap is a product decision — a full change history is a
    // report with its own screen, deferred deliberately — and this line is what stops the
    // truncation being silent.
    expect(element.querySelector('[data-testid="history-more"]')?.textContent).toContain('4');
  });

  it('says nothing about a tail when there is none', async () => {
    const fixture = await render(null);
    const element = await open(fixture, { events: [event()], total: 1, limit: 3 });

    expect(element.querySelector('[data-testid="history-more"]')).toBeNull();
  });

  it('falls back to the approval provenance for a protocol with no acts — the seeded case', async () => {
    // The 150 seeded protocols were approved by the V5 migration and have no ledger rows. Showing
    // nothing would let a reader assume the approval came from somewhere it did not; naming
    // system:corpus-seed says outright that no human reviewed this.
    const fixture = await render({
      state: 'APPROVED',
      approvedBy: 'system:corpus-seed',
      approvedAt: '2026-08-12T07:00:00Z',
    });
    const element = await open(fixture, { events: [], total: 0, limit: 3 });

    expect(element.querySelector('[data-testid="history-list"]')).toBeNull();
    const seeded = element.querySelector('[data-testid="history-seeded"]')?.textContent ?? '';
    expect(seeded).toContain('system:corpus-seed');
    expect(seeded).toContain('12.08.2026');
    // And the sentence that explains what that actor means, only for a system actor.
    expect(element.querySelector('[data-testid="history-seeded-note"]')?.textContent).toContain(
      'nicht von einer Person',
    );
  });

  it('names a human approver without the seed sentence', async () => {
    const fixture = await render({
      state: 'APPROVED',
      approvedBy: 'admin',
      approvedAt: '2026-08-12T07:00:00Z',
    });
    const element = await open(fixture, { events: [], total: 0, limit: 3 });

    expect(element.querySelector('[data-testid="history-seeded"]')?.textContent).toContain('admin');
    expect(element.querySelector('[data-testid="history-seeded-note"]')).toBeNull();
  });

  it('renders NO section at all when there is nothing to say', async () => {
    // THE EMPTY CASE. Not an empty box and not a "no history yet" line: an unapproved protocol
    // nobody has touched has no provenance and no acts, and a heading over nothing is furniture.
    const fixture = await render({ state: 'UNAPPROVED', approvedBy: null, approvedAt: null });
    const element = await open(fixture, { events: [], total: 0, limit: 3 });

    expect(element.querySelector('[data-testid="protocol-history"]')).toBeNull();
    expect(element.querySelector('[data-testid="history-list"]')).toBeNull();
  });

  it('never asks for a history on the citation path — the shop floor is refused it', async () => {
    // The endpoint answers 403 for those roles by design: who corrected what names colleagues in
    // connection with mistakes. The viewer does not ask rather than asking and swallowing a 403,
    // so httpMock.verify() below is the assertion.
    const fixture = await render(
      { state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-12T07:00:00Z' },
      'citation',
    );
    const element = await open(fixture, null, `/api/protocols/${PROTOCOL_ID}/document`);

    expect(element.querySelector('[data-testid="protocol-history"]')).toBeNull();
  });

  it('keeps the document readable when the history call fails', async () => {
    // FAILS QUIET, deliberately. The dialog exists to show a protocol; a red box about an
    // unreachable audit trail beside a document that loaded perfectly would read as though the
    // document were the problem.
    const fixture = await render({ state: 'UNAPPROVED', approvedBy: null, approvedAt: null });
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#opener') as HTMLButtonElement).click();
    await fixture.whenStable();

    httpMock
      .expectOne(MODERATION_DOCUMENT_URL)
      .flush(new Blob([WELL_FORMED], { type: 'text/plain' }));
    httpMock.expectOne(HISTORY_URL).flush('', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="protocol-body"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="protocol-failure"]')).toBeNull();
    expect(element.querySelector('[data-testid="protocol-history"]')).toBeNull();
  });

  it('does not carry one protocol’s history under the next protocol’s document', async () => {
    // The failure mode of every signal held across a reopen, and on this screen it would attribute
    // one protocol's corrections to another.
    const fixture = await render(null);
    await open(fixture, { events: [event({ comment: 'erste Freigabe' })], total: 1, limit: 3 });

    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('[data-testid="protocol-close"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.componentInstance.id.set(null);
    await fixture.whenStable();

    (element.querySelector('#opener') as HTMLButtonElement).click();
    await fixture.whenStable();
    httpMock
      .expectOne(MODERATION_DOCUMENT_URL)
      .flush(new Blob([WELL_FORMED], { type: 'text/plain' }));
    // Answered slowly on purpose: the assertion is about the gap BEFORE the second answer lands.
    const pending = httpMock.expectOne(HISTORY_URL);
    await fixture.whenStable();

    expect(
      element.querySelector('[data-testid="history-comment"]')?.textContent ?? '',
    ).not.toContain('erste Freigabe');

    pending.flush({ events: [event({ comment: 'zweite Freigabe' })], total: 1, limit: 3 });
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="history-comment"]')?.textContent).toContain(
      'zweite Freigabe',
    );
  });
});
