import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Citation, QueryAnswer } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { Search, filenameOf, toSegments } from './search';

/**
 * The search view's job is to make the two answer modes impossible to confuse (NFR-2) and to say
 * something useful when the NFR-7 guards refuse a question. Those are the assertions here; the
 * grounding itself is the backend's and is tested there.
 */
describe('Search', () => {
  let httpMock: HttpTestingController;

  const MACHINE = '0f9c5b01-0000-4000-8000-000000000001';

  const CITATION: Citation = {
    label: 'P1',
    protocolId: '0f9c5b02-0000-4000-8000-000000000001',
    title: 'E-47 Druckabfall im Presshub',
    errorCode: 'E-47',
    incidentDate: '2024-10-08',
    similarity: 0.6896,
  };

  const MODE_A: QueryAnswer = {
    mode: 'A',
    answer: 'Die Presse kommt nicht auf Druck. [P1] Ursache ist eine innere Leckage. [P1]',
    language: 'de',
    claims: [
      { text: 'Die Presse kommt nicht auf Druck.', source: 'P1' },
      { text: 'Ursache ist eine innere Leckage.', source: 'P1' },
    ],
    citations: [CITATION],
  };

  const MODE_B: QueryAnswer = {
    mode: 'B',
    answer:
      'Zu dieser Frage liegt kein Protokoll im Bestand vor.\nDosierpumpen prüfen.\nFüllstandssensoren prüfen.',
    language: 'de',
    claims: [],
    citations: [],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Search],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use('de');
  });

  afterEach(() => httpMock.verify());

  /** Creates the component and answers the machine list it loads on construction. */
  async function render() {
    const fixture = TestBed.createComponent(Search);
    httpMock
      .expectOne('/api/machines')
      .flush([{ id: MACHINE, machineNo: 'PR-03', name: 'Presse 3', type: 'Presse', location: null }]);
    await fixture.whenStable();
    return fixture;
  }

  /** Fills the form, submits, and answers the query with `response` or fails it with `status`. */
  async function ask(
    fixture: Awaited<ReturnType<typeof render>>,
    response: QueryAnswer | null,
    status?: number,
    body?: unknown,
  ) {
    const element = fixture.nativeElement as HTMLElement;
    const machine = element.querySelector('[data-testid="machine-picker"]') as HTMLSelectElement;
    machine.value = MACHINE;
    machine.dispatchEvent(new Event('change'));
    const question = element.querySelector('[data-testid="question-input"]') as HTMLTextAreaElement;
    question.value = 'Presse kommt nicht auf Druck, E-47';
    question.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).click();
    const request = httpMock.expectOne('/api/query');
    if (response) {
      request.flush(response);
    } else {
      request.flush(body ?? {}, { status: status ?? 500, statusText: 'error' });
    }
    await fixture.whenStable();
    return element;
  }

  // -------------------------------------------------------------------------------------
  // Mode A
  // -------------------------------------------------------------------------------------

  it('renders a sourced answer with its citations and source links', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_A);

    expect(element.querySelector('[data-testid="answer-mode-a"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="mode-badge"]')?.textContent).toContain(
      'Belegte Antwort',
    );
    expect(element.querySelector('[data-testid="sources"]')?.textContent).toContain(
      'E-47 Druckabfall im Presshub',
    );
    expect(element.querySelector('[data-testid="source-link"]')).not.toBeNull();
    expect(element.querySelectorAll('[data-testid="citation-marker"]').length).toBe(2);
  });

  it('tags the answer with the language the backend pinned it to, not the UI language', async () => {
    TestBed.inject(I18nService).use('en');
    const fixture = await render();
    const element = await ask(fixture, MODE_A);

    // Nothing is translated anywhere in this system: an English interface still shows the German
    // answer the German question earned.
    expect(element.querySelector('[data-testid="answer-text"]')?.getAttribute('lang')).toBe('de');
    expect(element.querySelector('[data-testid="mode-badge"]')?.textContent).toContain(
      'Sourced answer',
    );
  });

  it('shows the similarity as a percentage a reader can compare', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_A);

    expect(element.querySelector('[data-testid="sources"]')?.textContent).toContain('69 %');
  });

  // -------------------------------------------------------------------------------------
  // Opening a source — the 401 defect and its guard
  // -------------------------------------------------------------------------------------

  describe('opening a source', () => {
    /**
     * THE REGRESSION GUARD for the defect this suite exists to prevent.
     *
     * A raw `/api/...` href looks correct and is not: a browser-followed anchor is a fresh
     * navigation that never reaches Angular's interceptor, so it carries no Bearer token, and the
     * backend is a stateless JWT resource server with no cookie fallback. Every click answered
     * 401. Nothing in a test that opens the document *through HttpClient* can catch that, which is
     * exactly how it shipped — so the assertion is about the rendered DOM, not about the request.
     */
    it('renders no source link the browser could follow unauthenticated', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      const anchors = [...element.querySelectorAll('a')];
      expect(anchors.length).toBeGreaterThan(0);

      const navigable = anchors
        .map((anchor) => anchor.getAttribute('href'))
        .filter((href): href is string => href !== null && href.includes('/api/'));

      expect(navigable, 'these hrefs would navigate without a token and answer 401').toEqual([]);
    });

    it('fetches the document through the API service and opens the blob', async () => {
      const opened = { location: { href: '' }, close: vi.fn() };
      const open = vi.spyOn(window, 'open').mockReturnValue(opened as unknown as Window);
      const createObjectURL = vi
        .spyOn(URL, 'createObjectURL')
        .mockReturnValue('blob:fake-object-url');

      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      (element.querySelector('[data-testid="source-link"]') as HTMLElement).click();
      // The request goes through HttpClient, which is the whole point: that is the path the
      // OAuth interceptor is on.
      const request = httpMock.expectOne(
        '/api/protocols/0f9c5b02-0000-4000-8000-000000000001/document',
      );
      expect(request.request.responseType).toBe('blob');
      request.flush(new Blob(['Symptom: kein Druck'], { type: 'text/plain' }), {
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      });
      await fixture.whenStable();

      expect(createObjectURL).toHaveBeenCalled();
      // Opened synchronously on the click, then pointed at the blob — an async window.open is
      // what popup blockers stop.
      expect(open).toHaveBeenCalledWith('', '_blank');
      expect(opened.location.href).toBe('blob:fake-object-url');

      open.mockRestore();
      createObjectURL.mockRestore();
    });

    it('opens the source from an inline citation marker too', async () => {
      const opened = { location: { href: '' }, close: vi.fn() };
      const open = vi.spyOn(window, 'open').mockReturnValue(opened as unknown as Window);
      vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake-object-url');

      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      // Both places link a source, so both had the defect and both are covered.
      (element.querySelector('[data-testid="citation-marker"]') as HTMLElement).click();
      httpMock
        .expectOne('/api/protocols/0f9c5b02-0000-4000-8000-000000000001/document')
        .flush(new Blob(['x'], { type: 'text/plain' }));
      await fixture.whenStable();

      expect(opened.location.href).toBe('blob:fake-object-url');
      open.mockRestore();
      vi.restoreAllMocks();
    });

    it('says so when the original protocol can no longer be opened', async () => {
      const opened = { location: { href: '' }, close: vi.fn() };
      const open = vi.spyOn(window, 'open').mockReturnValue(opened as unknown as Window);

      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      (element.querySelector('[data-testid="source-link"]') as HTMLElement).click();
      httpMock
        .expectOne('/api/protocols/0f9c5b02-0000-4000-8000-000000000001/document')
        .flush(new Blob(), { status: 404, statusText: 'Not Found' });
      await fixture.whenStable();

      // Clicking into silence is the worst outcome: the claim above still rests on this protocol,
      // and the reader is entitled to know the evidence is unreachable.
      expect(element.querySelector('[data-testid="document-failure"]')?.textContent).toContain(
        'lässt sich nicht mehr öffnen',
      );
      // The blank tab opened on the click is closed rather than left staring at about:blank.
      expect(opened.close).toHaveBeenCalled();
      open.mockRestore();
    });

    it('shows the link as busy while the document is being fetched', async () => {
      const open = vi
        .spyOn(window, 'open')
        .mockReturnValue({ location: { href: '' }, close: vi.fn() } as unknown as Window);

      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      (element.querySelector('[data-testid="source-link"]') as HTMLElement).click();
      await fixture.whenStable();

      // The fetch is a round trip, and on a phone it is a visible one.
      expect(element.querySelector('[data-testid="source-opening"]')).not.toBeNull();
      expect(element.querySelector('[data-testid="source-link"]')?.getAttribute('aria-busy')).toBe(
        'true',
      );

      httpMock
        .expectOne('/api/protocols/0f9c5b02-0000-4000-8000-000000000001/document')
        .flush(new Blob(['x'], { type: 'text/plain' }));
      await fixture.whenStable();

      expect(element.querySelector('[data-testid="source-opening"]')).toBeNull();
      open.mockRestore();
      vi.restoreAllMocks();
    });
  });

  // -------------------------------------------------------------------------------------
  // Mode B
  // -------------------------------------------------------------------------------------

  it('renders an ungrounded answer with no source area at all', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_B);

    expect(element.querySelector('[data-testid="answer-mode-b"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="mode-badge"]')?.textContent).toContain(
      'Allgemeiner Vorschlag',
    );
    // Not "an empty source list" — no source area exists. An empty "Sources" heading invites the
    // reader to assume the sources are merely missing rather than absent by definition.
    expect(element.querySelector('[data-testid="sources"]')).toBeNull();
    expect(element.querySelector('[data-testid="source-link"]')).toBeNull();
    expect(element.querySelector('[data-testid="citation-marker"]')).toBeNull();
  });

  it('renders the ungrounded steps as a numbered list', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_B);

    expect(element.querySelectorAll('[data-testid="answer-text"] li').length).toBe(3);
  });

  it('never shows the two modes at the same time', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_B);

    expect(element.querySelector('[data-testid="answer-mode-a"]')).toBeNull();
  });

  // -------------------------------------------------------------------------------------
  // The NFR-7 guards, as a person reads them
  // -------------------------------------------------------------------------------------

  it('explains a rate limit as something to wait out', async () => {
    const fixture = await render();
    const element = await ask(fixture, null, 429);

    expect(element.querySelector('[data-testid="failure"]')?.textContent).toContain(
      'Zu viele Fragen',
    );
  });

  it('explains an exhausted daily budget as something that returns tomorrow', async () => {
    const fixture = await render();
    const element = await ask(fixture, null, 503, { reason: 'BUDGET_EXHAUSTED' });

    expect(element.querySelector('[data-testid="failure"]')?.textContent).toContain('Tageslimit');
  });

  it('tells an unreachable provider apart from a spent budget, on the same status', async () => {
    // Both are 503. Showing the budget message for an outage would tell the user to come back
    // tomorrow when the right advice is to try again in a minute.
    const fixture = await render();
    const element = await ask(fixture, null, 503, { reason: 'PROVIDER_UNAVAILABLE' });

    expect(element.querySelector('[data-testid="failure"]')?.textContent).toContain(
      'nicht erreichbar',
    );
  });

  it('clears the previous answer while the next one is being prepared', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_A);
    expect(element.querySelector('[data-testid="answer-mode-a"]')).not.toBeNull();

    (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // Leaving the old answer up for the 5-20 s this takes would show one machine's answer under
    // another machine's name.
    expect(element.querySelector('[data-testid="answer-mode-a"]')).toBeNull();
    expect(element.querySelector('[data-testid="working"]')).not.toBeNull();
    expect((element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).disabled).toBe(
      true,
    );
    httpMock.expectOne('/api/query').flush(MODE_A);
    await fixture.whenStable();
  });

  it('refuses to submit without a machine, before spending a provider call', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const question = element.querySelector('[data-testid="question-input"]') as HTMLTextAreaElement;
    question.value = 'Etwas';
    question.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="validation"]')?.textContent).toContain('Maschine');
    // httpMock.verify() in afterEach proves no query was sent.
  });

  it('stays usable when the machine list cannot be loaded', async () => {
    const fixture = TestBed.createComponent(Search);
    httpMock.expectOne('/api/machines').error(new ProgressEvent('error'), { status: 503 });
    await fixture.whenStable();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="machines-error"]'),
    ).not.toBeNull();
  });
});

// -----------------------------------------------------------------------------------------
// The marker split, on its own
// -----------------------------------------------------------------------------------------

describe('toSegments', () => {
  const citation: Citation = {
    label: 'P1',
    protocolId: 'id-1',
    title: 'Titel',
    errorCode: null,
    incidentDate: null,
    similarity: 0.7,
  };

  it('turns a known marker into a linked segment and keeps the prose around it', () => {
    const segments = toSegments('Erste Aussage. [P1] Zweite.', [citation]);

    expect(segments.map((segment) => segment.kind)).toEqual(['text', 'marker', 'text']);
    expect(segments[1].citation).toBe(citation);
  });

  it('leaves a marker with no matching citation as plain text', () => {
    // The backend already dropped every claim citing a source it did not retrieve, so a marker
    // that survives to here belongs to prose. Inventing a link would be the client asserting
    // something the server did not.
    const segments = toSegments('Aussage [P9] Ende.', [citation]);

    expect(segments.every((segment) => segment.kind === 'text')).toBe(true);
    expect(segments.map((segment) => segment.value).join('')).toBe('Aussage [P9] Ende.');
  });

  it('handles an answer with no markers at all', () => {
    expect(toSegments('Nur Text.', [citation])).toEqual([{ kind: 'text', value: 'Nur Text.' }]);
  });
});

describe('filenameOf', () => {
  it('prefers the UTF-8 form the backend sends alongside the plain one', () => {
    const header =
      'inline; filename="PR-03-Olleckage.txt"; filename*=UTF-8\'\'PR-03-%C3%96lleckage.txt';

    expect(filenameOf(header)).toBe('PR-03-Ölleckage.txt');
  });

  it('falls back to the plain filename, then to a default', () => {
    expect(filenameOf('inline; filename="PR-03-E-47.txt"')).toBe('PR-03-E-47.txt');
    expect(filenameOf(null)).toBe('protokoll.txt');
  });
});
