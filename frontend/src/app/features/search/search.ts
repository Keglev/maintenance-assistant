import { DOCUMENT } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Citation, Machine, QueryAnswer } from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';

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
  imports: [FormsModule],
  templateUrl: './search.html',
  styleUrl: './search.css',
})
export class Search implements OnDestroy {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);
  // Injected rather than reached for as a global: it is the seam a test uses to drive the
  // popup-blocked path without opening real windows.
  private readonly document = inject(DOCUMENT);

  protected readonly t = this.i18n.t;

  protected readonly machines = signal<Machine[]>([]);
  protected readonly machinesFailed = signal(false);
  protected readonly machineId = signal('');
  protected readonly question = signal('');

  protected readonly answer = signal<QueryAnswer | null>(null);
  protected readonly asking = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly validation = signal<'question' | 'machine' | null>(null);

  /** The protocol whose document is being fetched, so that one link can show it is working. */
  protected readonly openingId = signal<string | null>(null);
  /** Why opening a source failed — reported separately from a failed question. */
  protected readonly documentFailure = signal<ApiFailure | null>(null);

  /** Object URLs still alive, so none is leaked when the component goes away. */
  private readonly objectUrls = new Set<string>();

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

  /** Anything still held when the view goes away is released now rather than at page unload. */
  ngOnDestroy(): void {
    this.objectUrls.forEach((objectUrl) => URL.revokeObjectURL(objectUrl));
    this.objectUrls.clear();
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
   * Opens the protocol behind a citation.
   *
   * **Why this is a handler and not an `href`.** It was an `href`, and it did not work: a
   * browser-followed anchor is a fresh navigation that never passes through Angular's interceptor
   * chain, so it carries no `Authorization` header, and the backend is a stateless JWT resource
   * server with no cookie fallback — every click answered 401. So the document is fetched by
   * HttpClient, which the interceptor does reach, and handed to the browser as an object URL.
   *
   * The tab is opened **synchronously**, before the request starts, and pointed at the blob when it
   * arrives. Opening it in the response callback instead would be an asynchronous `window.open`,
   * which popup blockers exist to stop; this way the window is a direct consequence of the click.
   */
  protected openSource(protocolId: string, event?: Event): void {
    event?.preventDefault();
    if (this.openingId()) {
      return;
    }
    this.documentFailure.set(null);
    this.openingId.set(protocolId);

    const tab = this.document.defaultView?.open('', '_blank') ?? null;

    this.api.getDocument(protocolId).subscribe({
      next: (response) => {
        this.openingId.set(null);
        const blob = response.body;
        if (!blob) {
          this.documentFailure.set('generic');
          tab?.close();
          return;
        }
        // The backend states the type and the charset; honouring it is what makes a German
        // protocol render as text rather than download as bytes.
        const typed = new Blob([blob], {
          type: response.headers.get('Content-Type') ?? 'text/plain;charset=UTF-8',
        });
        const objectUrl = URL.createObjectURL(typed);
        this.trackObjectUrl(objectUrl);

        if (tab) {
          tab.location.href = objectUrl;
        } else {
          // The popup was blocked anyway. A download is a worse experience than a tab and a far
          // better one than nothing, and it needs no permission.
          this.downloadFallback(objectUrl, filenameOf(response.headers.get('Content-Disposition')));
        }
      },
      error: (error: unknown) => {
        this.openingId.set(null);
        tab?.close();
        this.documentFailure.set(classify(error));
      },
    });
  }

  /** Whether this particular source is the one currently being fetched. */
  protected isOpening(protocolId: string): boolean {
    return this.openingId() === protocolId;
  }

  /**
   * Object URLs pin their blob in memory until revoked, so a technician clicking through six
   * sources would otherwise leave six documents allocated for the life of the tab. Revoking
   * immediately is not an option — the new tab has not finished reading it yet — so each is held
   * briefly and then released.
   */
  private trackObjectUrl(objectUrl: string): void {
    this.objectUrls.add(objectUrl);
    setTimeout(() => {
      URL.revokeObjectURL(objectUrl);
      this.objectUrls.delete(objectUrl);
    }, OBJECT_URL_TTL_MS);
  }

  private downloadFallback(objectUrl: string, filename: string): void {
    const anchor = this.document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = filename;
    anchor.click();
  }

  /** Percent, rounded — a reader compares 69 % with 56 %, not 0.6896 with 0.5566. */
  protected similarityPercent(citation: Citation): number {
    return Math.round(citation.similarity * 100);
  }
}

/**
 * How long an object URL is kept alive after the tab has been pointed at it.
 *
 * Long enough for a slow tab to load it, short enough that clicking through a source list does not
 * accumulate blobs. Revoking on the new tab's load event would be tighter, but a cross-document
 * object URL gives no reliable load signal to the opener.
 */
const OBJECT_URL_TTL_MS = 60_000;

/** The readable filename the backend put in `Content-Disposition`, for the download fallback. */
export function filenameOf(contentDisposition: string | null): string {
  if (!contentDisposition) {
    return 'protokoll.txt';
  }
  // The UTF-8 form first: the backend sends both, and it is the one that survives umlauts.
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition);
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1]);
    } catch {
      // A malformed header is not worth failing a download over.
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(contentDisposition);
  return plain ? plain[1] : 'protokoll.txt';
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
      citation
        ? { kind: 'marker', value: match[1], citation }
        : { kind: 'text', value: match[0] },
    );
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < answer.length) {
    segments.push({ kind: 'text', value: answer.slice(lastIndex) });
  }
  return segments;
}
