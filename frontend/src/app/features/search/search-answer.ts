import { DOCUMENT } from '@angular/common';
import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { Citation, QueryAnswer } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { SearchSources, SourceFocus } from './search-sources';

/**
 * One piece of the answer text: either prose, or a citation marker that links to its source.
 *
 * The answer arrives with its `[P1]` markers already in the text and its claims already validated
 * against what was retrieved. Splitting it here is presentation only — the markers become links.
 * Nothing is re-parsed, reordered or filtered: the backend decided what the answer says, and a
 * client that second-guessed it would be a second, weaker citation check in the place least able to
 * make one.
 */
interface AnswerSegment {
  readonly kind: 'text' | 'marker';
  readonly value: string;
  /** For a marker: the citation it points at, when the label is one of the answer's sources. */
  readonly citation?: Citation;
}

/**
 * How tall an answer may be before it is clamped, as a share of the viewport.
 *
 * Half the screen: enough that most answers are never touched, little enough that a long one does
 * not push its own sources — and everything after them — off the bottom of the page.
 */
const CLAMP_VIEWPORT_SHARE = 0.5;

/** Below this the extra pixels are not worth a control; a two-line drawer is noise. */
const CLAMP_TOLERANCE_PX = 24;

/**
 * The answer block: the two modes, the citation markers, and the source list under them.
 *
 * <p>Its own component for two reasons. It is where the clamp below has to live — the drawer and
 * the citation markers must be able to talk to each other without the search view brokering it —
 * and the search view was otherwise carrying the markup and the stylesheet for something that is
 * not about asking a question.
 *
 * <p><b>The clamp is visual and nothing else.</b> The full answer is in the DOM at all times: it is
 * never truncated, never paginated and never split. A Mode A answer's claims each name the source
 * they came from, and cutting the text would separate a claim from its citation — the exact
 * guarantee NFR-2 rests on. `max-height` and `overflow: hidden` are the whole mechanism, so
 * expanding reveals text that was already there rather than fetching or rebuilding anything.
 */
@Component({
  selector: 'app-search-answer',
  imports: [SearchSources],
  templateUrl: './search-answer.html',
  styleUrl: './search-answer.css',
})
export class SearchAnswer {
  private readonly i18n = inject(I18nService);
  private readonly document = inject(DOCUMENT);

  protected readonly t = this.i18n.t;

  readonly answer = input.required<QueryAnswer>();
  /** What to call the machine on a source card read on its own. */
  readonly machine = input('');

  /**
   * Whether THIS answer was produced with the approved-only facet on.
   *
   * The state of the answer, not of the checkbox: a reader who toggles the facet after asking has
   * not changed what they are looking at, and a note that flipped with the control would describe a
   * search that was never run.
   */
  readonly approvedOnly = input(false);

  /** A source's title was activated — the search view owns the viewer dialog. */
  readonly opened = output<Citation>();

  /** The reader asked to search the whole corpus after all — the search view re-runs the query. */
  readonly widen = output<void>();

  private readonly body = viewChild<ElementRef<HTMLElement>>('answerBody');

  /** Whether the answer is long enough to be worth a drawer. Measured, not guessed. */
  protected readonly overflowing = signal(false);
  protected readonly expanded = signal(false);

  protected readonly clamped = computed(() => this.overflowing() && !this.expanded());

  /** The source a citation marker last pointed at; handed to the list, which reveals it. */
  protected readonly focusedSource = signal<SourceFocus | null>(null);

  protected readonly segments = computed<AnswerSegment[]>(() => {
    const current = this.answer();
    return current.mode === 'A' ? toSegments(current.answer, current.citations) : [];
  });

  /**
   * How many of the cited sources nobody has approved.
   *
   * <p>Said ONCE at the answer level, in addition to the per-source markers below, and both are
   * needed. Decision 1 of 2026-08-11 keeps unapproved protocols searchable and accepts that they are
   * less reliable to troubleshoot from — the price of that trade is that the reader is told. A
   * technician who has to read down a list of five source cards to discover that one of them is
   * unreviewed has effectively not been told: the answer is what they act on, and on a tablet the
   * sources may not even be on screen.
   *
   * <p>Zero when every source is approved, which is the ordinary case and renders nothing at all.
   */
  protected readonly unapprovedCount = computed(
    () => this.answer().citations.filter((citation) => !citation.approved).length,
  );

  /** Mode B's steps: the backend joins them with newlines, and they render as a numbered list. */
  protected readonly steps = computed<string[]>(() => {
    const current = this.answer();
    if (current.mode !== 'B') {
      return [];
    }
    return current.answer
      .split('\n')
      .map((step) => step.trim())
      .filter((step) => step.length > 0);
  });

  constructor() {
    effect(() => {
      // A new answer is a new height, and it starts open until measured: an answer that clamped
      // itself before anyone knew how tall it was would flash a drawer onto a two-line reply.
      this.answer();
      this.expanded.set(false);
      this.overflowing.set(false);
      // After the browser has laid the new text out. Reading a height in the same tick that set the
      // text measures the old one.
      setTimeout(() => this.measure());
    });
  }

  /**
   * Decides whether the drawer is needed, by measuring the rendered answer.
   *
   * <p>Measured rather than counted in characters: how tall an answer is depends on the viewport,
   * the text scale the reader chose (the design system is rem-based, so "large" genuinely means
   * taller) and where the lines break. A character threshold would clamp a short answer on a phone
   * and miss a long one on a desktop.
   */
  protected measure(): void {
    const element = this.body()?.nativeElement;
    if (!element) {
      return;
    }
    const limit = this.limitPx();
    this.overflowing.set(element.scrollHeight > limit + CLAMP_TOLERANCE_PX);
  }

  /** Half the viewport, from the same window the element is rendered in. */
  private limitPx(): number {
    const view = this.document.defaultView;
    return (view?.innerHeight ?? 0) * CLAMP_VIEWPORT_SHARE;
  }

  protected toggle(): void {
    this.expanded.update((open) => !open);
  }

  /**
   * Follows a `[P1]` marker to the card it names.
   *
   * <p><b>Expands first, then points.</b> A marker that scrolled to a source while the answer above
   * it was still clamped — or worse, did nothing visible because the clamp swallowed the movement —
   * is the #26 bug class returning in a new costume: a citation control that answers a click with
   * nothing. Opening the drawer is therefore part of following the marker, not a separate step the
   * reader has to think of.
   *
   * <p>A fresh object every time, because a signal set to the value it already holds notifies
   * nobody and the second click on the same marker has to work as well as the first.
   */
  protected showSource(citation: Citation): void {
    this.expanded.set(true);
    this.focusedSource.set({ protocolId: citation.protocolId });
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
  const pattern = /\[(P\d+)\]/gi;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(answer)) !== null) {
    const citation = byLabel.get(match[1].toUpperCase());
    if (!citation) {
      continue;
    }
    if (match.index > lastIndex) {
      segments.push({ kind: 'text', value: answer.slice(lastIndex, match.index) });
    }
    segments.push({ kind: 'marker', value: match[1], citation });
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < answer.length) {
    segments.push({ kind: 'text', value: answer.slice(lastIndex) });
  }
  return segments;
}
