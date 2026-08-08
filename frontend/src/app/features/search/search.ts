import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Citation, Machine, QueryAnswer } from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { ProtocolDialog } from '../../shared/protocol/protocol-dialog';

/**
 * One piece of the answer text: either prose, or a citation marker that links to its source.
 *
 * The answer arrives with its `[P1]` markers already in the text and its claims already validated
 * against what was retrieved. Splitting it here is presentation only — the markers become links.
 * Nothing is re-parsed, reordered or filtered: the backend decided what the answer says, and a
 * client that second-guessed it would be a second, weaker citation check in the place least able to
 * make one.
 */
export interface AnswerSegment {
  readonly kind: 'text' | 'marker';
  readonly value: string;
  /** For a marker: the citation it points at, when the label is one of the answer's sources. */
  readonly citation?: Citation;
}

/**
 * The search view (US-1/US-2).
 *
 * The two answer modes are rendered as two visually distinct blocks, which is not styling: NFR-2
 * makes the distinction the anti-hallucination mechanism. A Mode B answer that looked like a Mode A
 * answer would be the failure this application is built to prevent — general advice read as
 * documented plant experience. So Mode B gets its own colour, its own banner and, deliberately, no
 * source area at all rather than an empty one.
 */
@Component({
  selector: 'app-search',
  imports: [FormsModule, ProtocolDialog],
  templateUrl: './search.html',
  styleUrl: './search.css',
})
export class Search {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  protected readonly machines = signal<Machine[]>([]);
  protected readonly machinesFailed = signal(false);
  protected readonly machineId = signal('');
  protected readonly question = signal('');

  protected readonly answer = signal<QueryAnswer | null>(null);
  protected readonly asking = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly validation = signal<'question' | 'machine' | null>(null);

  /** The citation whose protocol the viewer is showing, or null when the viewer is closed. */
  protected readonly viewing = signal<Citation | null>(null);

  /** What to call the machine in the viewer's head: the picker's own label, not an id. */
  protected readonly machineLabel = computed(() => {
    const machine = this.machines().find((candidate) => candidate.id === this.machineId());
    return machine ? `${machine.machineNo} · ${machine.name}` : '';
  });

  /**
   * The answer text split into prose and citation markers.
   *
   * Computed rather than done in the template so the regular expression runs once per answer
   * instead of once per change detection pass.
   */
  protected readonly segments = computed<AnswerSegment[]>(() => {
    const current = this.answer();
    return current && current.mode === 'A' ? toSegments(current.answer, current.citations) : [];
  });

  /** Mode B's steps: the backend joins them with newlines, and they render as a numbered list. */
  protected readonly steps = computed<string[]>(() => {
    const current = this.answer();
    if (!current || current.mode !== 'B') {
      return [];
    }
    return current.answer
      .split('\n')
      .map((step) => step.trim())
      .filter((step) => step.length > 0);
  });

  constructor() {
    this.api.machines().subscribe({
      next: (machines) => this.machines.set(machines),
      // The view still renders and says the list is missing, rather than showing an empty picker
      // that looks like a plant with no machines in it.
      error: () => this.machinesFailed.set(true),
    });
  }

  protected ask(): void {
    if (this.asking()) {
      return;
    }
    const question = this.question().trim();
    if (!this.machineId()) {
      this.validation.set('machine');
      return;
    }
    if (!question) {
      this.validation.set('question');
      return;
    }

    this.validation.set(null);
    this.failure.set(null);
    // The previous answer is cleared before the new one is requested. Leaving it on screen for the
    // 5-20 seconds this takes would show one machine's answer under another machine's name.
    this.answer.set(null);
    this.asking.set(true);

    this.api.ask(question, this.machineId()).subscribe({
      next: (answer) => {
        this.answer.set(answer);
        this.asking.set(false);
      },
      error: (error: unknown) => {
        this.failure.set(classify(error));
        this.asking.set(false);
      },
    });
  }

  /**
   * Opens the protocol behind a citation, in a dialog, inside the application.
   *
   * **Why this is a handler and not an `href`.** It was an `href`, and it did not work: a
   * browser-followed anchor is a fresh navigation that never passes through Angular's interceptor
   * chain, so it carries no `Authorization` header, and the backend is a stateless JWT resource
   * server with no cookie fallback — every click answered 401.
   *
   * The first fix (#26) fetched the document through HttpClient and opened it in a new tab as an
   * object URL. That was right about the token and wrong about the destination: it dropped the
   * reader into the browser's raw text viewer, out of the application, which on a shop-floor tablet
   * is somewhere hard to come back from. The viewer dialog keeps the document here, and the whole
   * question of what a navigation carries stops applying.
   *
   * All this method does now is name the citation — the dialog owns the fetch, the parsing and the
   * download.
   */
  protected openSource(citation: Citation, event?: Event): void {
    event?.preventDefault();
    this.viewing.set(citation);
  }

  /** Percent, rounded — a reader compares 69 % with 56 %, not 0.6896 with 0.5566. */
  protected similarityPercent(citation: Citation): number {
    return Math.round(citation.similarity * 100);
  }
}

/**
 * Splits an answer into prose and `[Pn]` markers.
 *
 * A marker whose label is not among the citations is left as plain text rather than dropped or
 * linked: the backend already removed every claim citing a source it did not retrieve, so if one
 * still appears here it belongs to prose and inventing a link for it would be the client asserting
 * something the server did not.
 */
export function toSegments(answer: string, citations: readonly Citation[]): AnswerSegment[] {
  const byLabel = new Map(citations.map((citation) => [citation.label.toUpperCase(), citation]));
  const segments: AnswerSegment[] = [];
  const pattern = /\[([A-Za-z]\d+)\]/g;
  let lastIndex = 0;

  for (let match = pattern.exec(answer); match !== null; match = pattern.exec(answer)) {
    if (match.index > lastIndex) {
      segments.push({ kind: 'text', value: answer.slice(lastIndex, match.index) });
    }
    const citation = byLabel.get(match[1].toUpperCase());
    segments.push(
      citation ? { kind: 'marker', value: match[1], citation } : { kind: 'text', value: match[0] },
    );
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < answer.length) {
    segments.push({ kind: 'text', value: answer.slice(lastIndex) });
  }
  return segments;
}
