import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Citation, QueryAnswer } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { Search, toSegments } from './search';

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

  /** A protocol shaped like the corpus, so the viewer has something real to parse. */
  const PROTOCOL = [
    'WARTUNGSPROTOKOLL',
    '=================',
    '',
    'Maschine: PR-03',
    'Datum: 08.10.2024',
    '',
    'E-47 Druckabfall im Presshub',
    '',
    'Symptom:',
    'Presse kommt nicht auf Druck.',
  ].join('\n');

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

  describe('the source list', () => {
    /** An answer with more sources than fit under it — the case the collapse exists for. */
    function withSources(count: number): QueryAnswer {
      return {
        ...MODE_A,
        citations: Array.from({ length: count }, (_, index) => ({
          ...CITATION,
          label: `P${index + 1}`,
          protocolId: `protocol-${index + 1}`,
          title: `Quelle ${index + 1}`,
        })),
      };
    }

    it('leaves two sources open — collapsing a short list buys a click and nothing else', async () => {
      const fixture = await render();
      const element = await ask(fixture, withSources(2));

      expect(element.querySelector('[data-testid="toggle-all-sources"]')).toBeNull();
      for (const card of element.querySelectorAll('[data-testid="source-card"]')) {
        expect(card.getAttribute('data-expanded')).toBe('true');
      }
      expect(element.querySelector('[data-testid="source-toggle"]')).toBeNull();
    });

    it('collapses more than two, so the answer stays on screen', async () => {
      const fixture = await render();
      const element = await ask(fixture, withSources(5));

      const cards = element.querySelectorAll('[data-testid="source-card"]');
      expect(cards.length).toBe(5);
      for (const card of cards) {
        expect(card.getAttribute('data-expanded')).toBe('false');
      }
      // Every card still names itself while collapsed: title and match are what tell five apart.
      expect(cards[0].textContent).toContain('Quelle 1');
      expect(cards[0].textContent).toContain('69');
    });

    it('expands one card at a time, and all of them at once', async () => {
      const fixture = await render();
      const element = await ask(fixture, withSources(5));

      (element.querySelectorAll('[data-testid="source-toggle"]')[1] as HTMLButtonElement).click();
      await fixture.whenStable();

      let cards = element.querySelectorAll('[data-testid="source-card"]');
      expect(cards[1].getAttribute('data-expanded')).toBe('true');
      expect(cards[0].getAttribute('data-expanded')).toBe('false');

      (element.querySelector('[data-testid="toggle-all-sources"]') as HTMLButtonElement).click();
      await fixture.whenStable();

      cards = element.querySelectorAll('[data-testid="source-card"]');
      for (const card of cards) {
        expect(card.getAttribute('data-expanded')).toBe('true');
      }
    });

    it('a citation marker expands and highlights the card it names', async () => {
      const fixture = await render();
      const answer = withSources(5);
      const element = await ask(fixture, {
        ...answer,
        answer: 'Erst das eine. [P3] Dann das andere.',
        claims: [{ text: 'Erst das eine.', source: 'P3' }],
      });

      const cards = element.querySelectorAll('[data-testid="source-card"]');
      expect(cards[2].getAttribute('data-expanded')).toBe('false');

      (element.querySelector('[data-testid="citation-marker"]') as HTMLElement).click();
      await fixture.whenStable();

      // A MARKER MUST NEVER DEAD-END (#26 is the bug class). Scrolling to a card that shows a title
      // and no detail is a click that reads as "nothing happened", so it opens first.
      const after = element.querySelectorAll('[data-testid="source-card"]');
      expect(after[2].getAttribute('data-expanded')).toBe('true');
      expect(after[2].classList.contains('highlighted')).toBe(true);
      // And only that one: the click points at a source, it does not open the whole list.
      expect(after[0].getAttribute('data-expanded')).toBe('false');
    });

    it('answers the same marker twice — the second click has to work as well as the first', async () => {
      const fixture = await render();
      const element = await ask(fixture, {
        ...withSources(5),
        answer: 'Eins. [P3]',
        claims: [{ text: 'Eins.', source: 'P3' }],
      });

      const marker = element.querySelector('[data-testid="citation-marker"]') as HTMLElement;
      marker.click();
      await fixture.whenStable();
      (element.querySelectorAll('[data-testid="source-toggle"]')[2] as HTMLButtonElement).click();
      await fixture.whenStable();
      marker.click();
      await fixture.whenStable();

      // A signal set to the value it already holds notifies nobody; a reader who collapsed the card
      // and tapped the marker again would otherwise get nothing.
      expect(
        element.querySelectorAll('[data-testid="source-card"]')[2].getAttribute('data-expanded'),
      ).toBe('true');
    });

    it('never collapses or pages the answer text itself', async () => {
      const fixture = await render();
      const element = await ask(fixture, withSources(5));

      // NFR-2's Mode A guarantee is that every claim sits beside the source it came from. Splitting
      // the prose would break it, so the collapse applies to the list and never to the answer.
      expect(element.querySelector('[data-testid="answer-text"]')?.textContent).toContain(
        'Ursache ist eine innere Leckage.',
      );
    });
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

  it('keeps the example questions on screen while the question is being typed', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    const hint = element.querySelector('[data-testid="question-hint"]');
    expect(hint?.textContent).toContain('Sensorfehler');

    // The point of a hint that is not a placeholder: it is still there at the first keystroke,
    // which is when a first-time user is working out what kind of thing to ask for.
    const question = element.querySelector('[data-testid="question-input"]') as HTMLTextAreaElement;
    question.value = 'Presse';
    question.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="question-hint"]')?.textContent).toContain(
      'Sensorfehler',
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

    /**
     * Nothing opens a tab any more, and that is the point of this suite's rework.
     *
     * #26 fetched the blob through HttpClient and opened it as an object URL in a new tab. The
     * token problem it solved only existed because the document LEFT the application; the viewer
     * dialog keeps it here, so the tab, the synchronous `window.open` ordering and the
     * popup-blocker fallback are all gone rather than merely untested.
     */
    it('opens the protocol in a dialog inside the application, not in a new tab', async () => {
      const open = vi.spyOn(window, 'open');

      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      (element.querySelector('[data-testid="source-link"]') as HTMLElement).click();
      await fixture.whenStable();

      // The request still goes through HttpClient, which is the half of #26 that was right: that
      // is the path the OAuth interceptor is on.
      const request = httpMock.expectOne(
        '/api/protocols/0f9c5b02-0000-4000-8000-000000000001/document',
      );
      expect(request.request.responseType).toBe('blob');
      request.flush(new Blob([PROTOCOL], { type: 'text/plain' }), {
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      });
      await fixture.whenStable();

      expect(element.querySelector('[data-testid="protocol-dialog"]')).not.toBeNull();
      expect(open).not.toHaveBeenCalled();
      open.mockRestore();
    });

    it('an inline citation marker reveals its source card rather than opening the document', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      (element.querySelector('[data-testid="citation-marker"]') as HTMLElement).click();
      await fixture.whenStable();

      // CHANGED BEHAVIOUR, on purpose: a marker used to open the viewer straight away. It now takes
      // the reader to the source it names, expanded and highlighted, and they decide whether to
      // open it. The rule the marker must keep either way is that it never dead-ends — the card it
      // points at has to be visible and open when the click lands (see #26 for the bug class).
      const card = element.querySelector('[data-testid="source-card"]') as HTMLElement;
      expect(card.getAttribute('data-expanded')).toBe('true');
      expect(card.classList.contains('highlighted')).toBe(true);
      // No document is fetched by the marker itself; the title is what opens the protocol.
      httpMock.verify();
    });

    it('names the protocol and the machine in the viewer head', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      (element.querySelector('[data-testid="source-link"]') as HTMLElement).click();
      await fixture.whenStable();
      httpMock
        .expectOne('/api/protocols/0f9c5b02-0000-4000-8000-000000000001/document')
        .flush(new Blob([PROTOCOL], { type: 'text/plain' }));
      await fixture.whenStable();

      const dialog = element.querySelector('[data-testid="protocol-dialog"]') as HTMLElement;
      expect(dialog.textContent).toContain('E-47 Druckabfall im Presshub');
      expect(element.querySelector('[data-testid="protocol-machine"]')?.textContent).toContain(
        'Presse 3',
      );
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
