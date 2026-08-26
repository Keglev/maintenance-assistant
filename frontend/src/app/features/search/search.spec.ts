import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Citation, QueryAnswer } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { toSegments } from './search-answer';
import { Search } from './search';

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
    approved: true,
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
      .flush([
        { id: MACHINE, machineNo: 'PR-03', name: 'Presse 3', type: 'Presse', location: null },
      ]);
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

  // -------------------------------------------------------------------------------------
  // Approval (v1.2) — decision 1 of 2026-08-11 made visible
  // -------------------------------------------------------------------------------------

  describe('approval on an answer and its sources', () => {
    const UNAPPROVED_CITATION: Citation = {
      ...CITATION,
      protocolId: 'p-2',
      label: 'P2',
      approved: false,
    };

    it('marks an unapproved source on its card, in words', async () => {
      const fixture = await render();
      const element = await ask(fixture, {
        ...MODE_A,
        citations: [CITATION, UNAPPROVED_CITATION],
      });

      const states = [...element.querySelectorAll('[data-testid="approval-state"]')].map((node) =>
        node.getAttribute('data-approval'),
      );
      // One of each, in citation order: the mark belongs to the source, not to the answer as a
      // whole, or a reader could not tell WHICH claim rests on unreviewed text.
      expect(states).toEqual(['APPROVED', 'UNAPPROVED']);
      expect(element.querySelector('[data-testid="sources"]')?.textContent).toContain(
        'Nicht freigegeben',
      );
    });

    it('says it once at the answer level too, so nobody has to audit the source list', async () => {
      const fixture = await render();
      const element = await ask(fixture, {
        ...MODE_A,
        citations: [CITATION, UNAPPROVED_CITATION],
      });

      // A technician acts on the ANSWER. On a tablet the sources may not be on screen at all, and
      // having to work down five cards to discover that one of them is unreviewed is not being
      // told — it is being given the chance to find out.
      expect(element.querySelector('[data-testid="answer-unapproved"]')?.textContent).toContain(
        'Eine der Quellen',
      );
    });

    it('counts them, so "one of five" and "four of five" do not read the same', async () => {
      const fixture = await render();
      const element = await ask(fixture, {
        ...MODE_A,
        citations: [
          UNAPPROVED_CITATION,
          { ...UNAPPROVED_CITATION, protocolId: 'p-3', label: 'P3' },
        ],
      });

      expect(element.querySelector('[data-testid="answer-unapproved"]')?.textContent).toContain(
        '2 der Quellen',
      );
    });

    it('says nothing at the answer level when every source is approved', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);

      // The ordinary case, and it stays silent. A line that appeared on every answer would be one
      // readers learn to skip, and the day it says something they would skip that too.
      expect(element.querySelector('[data-testid="answer-unapproved"]')).toBeNull();
      expect(
        element.querySelector('[data-testid="approval-state"]')?.getAttribute('data-approval'),
      ).toBe('APPROVED');
    });

    it('carries the card state into the viewer rather than looking it up again', async () => {
      const fixture = await render();
      const element = await ask(fixture, { ...MODE_A, citations: [UNAPPROVED_CITATION] });

      (element.querySelector('[data-testid="source-link"]') as HTMLElement).click();
      await fixture.whenStable();
      httpMock
        .expectOne('/api/protocols/p-2/document')
        .flush(new Blob([PROTOCOL], { type: 'text/plain' }));
      await fixture.whenStable();
      await fixture.whenStable();

      // One fact, told once. A second fetch could also disagree with the card — an administrator
      // may have approved it in between — and a source list contradicting the document it links to
      // is worse than a slightly stale mark.
      expect(element.querySelector('[data-testid="protocol-machine"]')?.textContent).toContain(
        'Nicht freigegeben',
      );
    });
  });

  describe('the approved-only facet', () => {
    it('searches the whole corpus by default, unreviewed protocols included', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;

      const machine = element.querySelector('[data-testid="machine-picker"]') as HTMLSelectElement;
      machine.value = MACHINE;
      machine.dispatchEvent(new Event('change'));
      const question = element.querySelector(
        '[data-testid="question-input"]',
      ) as HTMLTextAreaElement;
      question.value = 'Presse kommt nicht auf Druck';
      question.dispatchEvent(new Event('input'));
      await fixture.whenStable();

      (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).click();
      const request = httpMock.expectOne('/api/query');

      // OFF is the DECISION of 2026-08-11, not a convenience: the admin may not review at a
      // weekend and the factory does not stop, so the protocol about the fault happening right now
      // has to be findable before anyone signs it off. Defaulting this on would quietly reinstate
      // the gate that decision refused.
      expect(request.request.body.approvedOnly).toBe(false);
      expect(
        (element.querySelector('[data-testid="approved-only"]') as HTMLInputElement).checked,
      ).toBe(false);
      request.flush(MODE_A);
      await fixture.whenStable();
    });

    it('narrows the search when the reader asks it to', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;

      const facet = element.querySelector('[data-testid="approved-only"]') as HTMLInputElement;
      facet.checked = true;
      facet.dispatchEvent(new Event('change'));
      await fixture.whenStable();

      const machine = element.querySelector('[data-testid="machine-picker"]') as HTMLSelectElement;
      machine.value = MACHINE;
      machine.dispatchEvent(new Event('change'));
      const question = element.querySelector(
        '[data-testid="question-input"]',
      ) as HTMLTextAreaElement;
      question.value = 'Presse kommt nicht auf Druck';
      question.dispatchEvent(new Event('input'));
      await fixture.whenStable();

      (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).click();
      const request = httpMock.expectOne('/api/query');
      expect(request.request.body.approvedOnly).toBe(true);
      request.flush(MODE_B);
      await fixture.whenStable();

      // THE EMPTY STATE, and it is a Mode B answer rather than an empty list: with retrieval
      // narrowed, nothing clears the threshold and the backend answers exactly as it would for a
      // genuine gap in the corpus. Without this line the reader concludes the plant has no protocol
      // on the fault, when what happened is that theirs is not signed off yet.
      expect(element.querySelector('[data-testid="approved-only-note"]')?.textContent).toContain(
        'nur in freigegebenen',
      );
    });

    it('re-asks against the whole corpus when the reader takes the way out', async () => {
      const fixture = await render();
      const element = fixture.nativeElement as HTMLElement;

      const facet = element.querySelector('[data-testid="approved-only"]') as HTMLInputElement;
      facet.checked = true;
      facet.dispatchEvent(new Event('change'));
      const machine = element.querySelector('[data-testid="machine-picker"]') as HTMLSelectElement;
      machine.value = MACHINE;
      machine.dispatchEvent(new Event('change'));
      const question = element.querySelector(
        '[data-testid="question-input"]',
      ) as HTMLTextAreaElement;
      question.value = 'Presse kommt nicht auf Druck';
      question.dispatchEvent(new Event('input'));
      await fixture.whenStable();

      (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).click();
      httpMock.expectOne('/api/query').flush(MODE_B);
      await fixture.whenStable();

      (element.querySelector('[data-testid="approved-only-widen"]') as HTMLButtonElement).click();
      await fixture.whenStable();

      // Re-asked rather than merely unchecked: the reader wants the answer, not the setting.
      const widened = httpMock.expectOne('/api/query');
      expect(widened.request.body.approvedOnly).toBe(false);
      widened.flush(MODE_A);
      await fixture.whenStable();

      expect(element.querySelector('[data-testid="answer-mode-a"]')).not.toBeNull();
      expect(element.querySelector('[data-testid="approved-only-note"]')).toBeNull();
    });

    it('describes the search that was run, not the state of the checkbox', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_B);

      // The answer on screen was asked WITHOUT the facet, so no note — and turning the facet on
      // afterwards must not retroactively explain a search nobody ran.
      expect(element.querySelector('[data-testid="approved-only-note"]')).toBeNull();

      const facet = element.querySelector('[data-testid="approved-only"]') as HTMLInputElement;
      facet.checked = true;
      facet.dispatchEvent(new Event('change'));
      await fixture.whenStable();

      expect(element.querySelector('[data-testid="approved-only-note"]')).toBeNull();
    });
  });

  describe('the long-answer drawer', () => {
    /**
     * Makes the rendered answer taller than the clamp allows.
     *
     * jsdom has no layout engine, so every element measures zero and the component would never see
     * an overflow. Defining `scrollHeight` is the smallest honest stand-in for a browser that
     * actually laid the text out — the logic under test is the comparison and what it drives, not
     * the arithmetic of line boxes.
     */
    function makeTall(element: HTMLElement, pixels: number) {
      const body = element.querySelector('.answer-body') as HTMLElement;
      Object.defineProperty(body, 'scrollHeight', { value: pixels, configurable: true });
      return body;
    }

    /**
     * Waits for the measurement the component schedules after a render.
     *
     * It measures in a macrotask on purpose — reading a height in the same tick that set the text
     * measures the OLD text — so the test has to let one turn of the event loop pass before the
     * answer knows how tall it is.
     */
    async function settle(fixture: Awaited<ReturnType<typeof render>>) {
      await new Promise((resolve) => setTimeout(resolve));
      fixture.detectChanges();
    }

    it('leaves a short answer exactly as it was — no clamp, no control', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);
      makeTall(element, 100);
      await settle(fixture);

      expect(element.querySelector('[data-testid="answer-toggle"]')).toBeNull();
      expect(element.querySelector('.answer-body')?.classList.contains('clamped')).toBe(false);
    });

    it('clamps an answer taller than half the viewport and offers the drawer', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);
      makeTall(element, 5_000);
      await settle(fixture);

      expect(element.querySelector('.answer-body')?.classList.contains('clamped')).toBe(true);
      const toggle = element.querySelector('[data-testid="answer-toggle"]') as HTMLButtonElement;
      expect(toggle).not.toBeNull();
      expect(toggle.textContent).toContain('Vollständige Antwort anzeigen');
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
    });

    /**
     * The regression the second design drill found, and the reason it could exist at all.
     *
     * The source list's distance from the answer was written as `.answer-body + app-search-sources`.
     * On a LONG answer the drawer toggle renders between those two, the adjacent-sibling combinator
     * stops matching, and "Vollständige Antwort anzeigen" ends up welded to QUELLEN — the one case
     * where the spacing matters most is the one case where the rule switched itself off.
     *
     * WHAT THIS TEST CAN AND CANNOT SEE: jsdom has no layout engine and no cascade worth trusting,
     * so it cannot assert a margin in pixels. What it CAN assert is the structural fact the old rule
     * depended on and the new one does not — that with the toggle present, the element in front of
     * the source list is the toggle and not `.answer-body`. That is precisely the condition that
     * broke the sibling selector, so a rule reintroduced in that shape would be provably wrong here
     * even though the gap itself is measured in a browser, by eye.
     */
    it('spaces the source list off whatever precedes it, toggle or answer body', async () => {
      // Short answer: the source list follows the answer body directly, which is the only case the
      // old sibling rule ever covered.
      const shortFixture = await render();
      const shortAnswer = await ask(shortFixture, MODE_A);
      makeTall(shortAnswer, 100);
      await settle(shortFixture);

      const shortSources = shortAnswer.querySelector('app-search-sources') as HTMLElement;
      expect(shortSources).not.toBeNull();
      expect(shortAnswer.querySelector('[data-testid="answer-toggle"]')).toBeNull();
      expect(shortSources.previousElementSibling?.classList.contains('answer-body')).toBe(true);

      // Long answer: the toggle is now in between, and `.answer-body + app-search-sources` no
      // longer matches anything at all.
      const longFixture = await render();
      const longAnswer = await ask(longFixture, MODE_A);
      makeTall(longAnswer, 5_000);
      await settle(longFixture);

      const toggle = longAnswer.querySelector('[data-testid="answer-toggle"]');
      expect(toggle).not.toBeNull();
      const longSources = longAnswer.querySelector('app-search-sources') as HTMLElement;
      expect(longSources).not.toBeNull();
      expect(longSources.previousElementSibling).toBe(toggle);
    });

    it('never truncates the answer — the whole text is in the DOM while clamped', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);
      makeTall(element, 5_000);
      await settle(fixture);

      // The clamp is a max-height and nothing else. Cutting the text would separate a claim from
      // the citation beside it, which is the one thing a Mode A answer must never do (NFR-2).
      const text = element.querySelector('[data-testid="answer-text"]')?.textContent ?? '';
      expect(text).toContain('Die Presse kommt nicht auf Druck.');
      expect(text).toContain('Ursache ist eine innere Leckage.');
      expect(element.querySelectorAll('[data-testid="citation-marker"]').length).toBe(2);
    });

    it('expands in place, and says so', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);
      makeTall(element, 5_000);
      await settle(fixture);

      (element.querySelector('[data-testid="answer-toggle"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(element.querySelector('.answer-body')?.classList.contains('clamped')).toBe(false);
      const toggle = element.querySelector('[data-testid="answer-toggle"]') as HTMLButtonElement;
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(toggle.textContent).toContain('Antwort einklappen');
    });

    it('a citation marker expands the answer first, then points at its card', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);
      makeTall(element, 5_000);
      await settle(fixture);
      expect(element.querySelector('.answer-body')?.classList.contains('clamped')).toBe(true);

      (element.querySelector('[data-testid="citation-marker"]') as HTMLElement).click();
      fixture.detectChanges();

      // A marker that pointed at a source from behind a clamp would answer a click with nothing
      // visible — the #26 bug class in a new costume. Expanding is part of following the marker.
      expect(element.querySelector('.answer-body')?.classList.contains('clamped')).toBe(false);
      const card = element.querySelector('[data-testid="source-card"]') as HTMLElement;
      expect(card.classList.contains('highlighted')).toBe(true);
    });

    it('starts open again when a new answer arrives', async () => {
      const fixture = await render();
      const element = await ask(fixture, MODE_A);
      makeTall(element, 5_000);
      await settle(fixture);
      (element.querySelector('[data-testid="answer-toggle"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      await ask(fixture, MODE_B);
      await settle(fixture);

      // A second question is a second answer: carrying the previous expansion over would clamp or
      // open it on a height nobody measured.
      expect(element.querySelector('[data-testid="answer-toggle"]')).toBeNull();
    });
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

  it('keeps the examples in the help panel, not in a grey line above the field', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;

    // The inline hint duplicated the help panel at lower quality: the same advice, in helper grey,
    // pushed between the label and the field it was about.
    expect(element.querySelector('[data-testid="question-hint"]')).toBeNull();

    // It lives here now, worked out as good/weak pairs and beside the form rather than inside it.
    // 'Kompressor startet nicht' was the first good example until 2026-08-20, when the list was
    // rebuilt around the code rule; the full-sentence category it stood for is still here.
    const examples = element.querySelector('[data-testid="search-help-examples"]');
    expect(examples?.textContent).toContain('Presse kommt nicht auf Druck');
    expect(examples?.textContent).toContain('frei');

    // The textarea keeps its placeholder, which is a different job: an example of the SHAPE of a
    // question, in the field, gone at the first keystroke.
    const question = element.querySelector('[data-testid="question-input"]') as HTMLTextAreaElement;
    expect(question.getAttribute('placeholder')).toContain('E-47');
  });

  /**
   * THE PANEL MUST STATE THE RULE THE RETRIEVAL CODE ACTUALLY IMPLEMENTS.
   *
   * Until 2026-08-20 it taught that a single word can never work. ADR-009 falsified that — a bare
   * "E-47" returns a grounded answer, because LexicalTerms matches any term carrying at least one
   * letter AND one digit — and Carlos's v1.3.0 drill found the panel contradicting the running
   * system. This test exists so the panel cannot drift out of agreement again silently: it pins the
   * BOTH halves of the distinction, because a panel that mentions codes but drops the generic-word
   * half would teach that any single word works, which is the opposite error.
   */
  it('teaches that a bare code works and a bare generic word does not, in both languages', async () => {
    const fixture = await render();
    const element = fixture.nativeElement as HTMLElement;
    const panel = element.querySelector('[data-testid="search-help"]');

    // The code half — stated as the rule, and shown as an example a reader can copy.
    expect(panel?.textContent).toContain('wortgenau');
    expect(panel?.textContent).toContain('Buchstaben UND Ziffern');
    expect(panel?.textContent).toContain('SV0410');
    expect(element.querySelector('[data-testid="search-help-examples"]')?.textContent).toContain(
      'E-47',
    );

    // The generic-word half, still true and still the more common mistake.
    expect(panel?.textContent).toContain('ganze kurze Sätze schlagen einzelne Wörter');

    // And the claim the live system disproved is gone rather than merely softened.
    expect(panel?.textContent).not.toContain('hat nichts, womit sie vergleichen kann');
  });

  it('teaches the same distinction in English', async () => {
    TestBed.inject(I18nService).use('en');
    const fixture = await render();
    const panel = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="search-help"]',
    );

    expect(panel?.textContent).toContain('word for word');
    expect(panel?.textContent).toContain('letters AND digits');
    expect(panel?.textContent).toContain('SV0410');
    expect(panel?.textContent).toContain('whole short sentences beat single words');
    expect(panel?.textContent).not.toContain('nothing to compare it against');
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

  /**
   * THE TIP LINE, and why it is asserted rather than left to the eye.
   *
   * Mode B is the moment a lazy query shows its consequence, and after ADR-009 there is something
   * useful to say at exactly that moment: name the code, because codes are matched literally. The
   * line is deliberately quiet, which is also what makes it easy to delete by accident — a quiet
   * line looks like decoration to anyone tidying this template later.
   *
   * It is asserted on the MODE B branch only. On a grounded answer the advice is noise: the reader
   * already got what they came for, and telling them how to have asked better is a rebuke.
   */
  it('offers the code tip on an ungrounded answer, and only there', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_B);

    const tip = element.querySelector('[data-testid="mode-b-tip"]');
    expect(tip?.textContent).toContain('Fehlercode');
    expect(tip?.textContent).toContain('wortgenau');

    // No role and no live region: this is an aside from the interface, not a second complaint about
    // the answer, and a Mode B block already carries its own warning.
    expect(tip?.getAttribute('role')).toBeNull();
  });

  it('does not offer the code tip on a grounded answer', async () => {
    const fixture = await render();
    const element = await ask(fixture, MODE_A);

    expect(element.querySelector('[data-testid="mode-b-tip"]')).toBeNull();
  });

  it('translates the code tip', async () => {
    TestBed.inject(I18nService).use('en');
    const fixture = await render();
    const element = await ask(fixture, MODE_B);

    expect(element.querySelector('[data-testid="mode-b-tip"]')?.textContent).toContain(
      'word for word',
    );
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
    expect(
      (element.querySelector('[data-testid="ask-button"]') as HTMLButtonElement).disabled,
    ).toBe(true);
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
    approved: true,
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
