import { DOCUMENT } from '@angular/common';
import {
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { Approval, ModerationEvent, ProtocolHistory } from '../../core/api/api.types';
import {
  ApiFailure,
  MaintenanceApiService,
  classify,
} from '../../core/api/maintenance-api.service';
import { AppDatePipe } from '../../core/i18n/app-date.pipe';
import { I18nService } from '../../core/i18n/i18n.service';
import { ApprovalStateBadge } from '../approval/approval-state';
import { Dialog } from '../dialog/dialog';
import { ProtocolDocument, filenameOf, parseProtocol } from './protocol-document';

/**
 * Which endpoint a viewer reads its document from.
 *
 * Three paths for the same file, and they differ in exactly one thing: who may take them, and what
 * they are allowed to serve. `citation` is the shop-floor path; `moderation` is the admin's view of
 * the live corpus; `archive` is the only door back to a protocol that was removed — the other two
 * answer 404 for it. A single endpoint with a flag would put that distinction in a parameter
 * instead of in the URL, where the authorisation rule can be read.
 */
type DocumentSource = 'citation' | 'moderation' | 'archive';

/** Kept as a map so adding a fourth path cannot become a third arm of a nested conditional. */
const DOCUMENT_SOURCES: Record<
  DocumentSource,
  (api: MaintenanceApiService, id: string) => ReturnType<MaintenanceApiService['getDocument']>
> = {
  citation: (api, id) => api.getDocument(id),
  moderation: (api, id) => api.getModerationDocument(id),
  archive: (api, id) => api.getArchivedDocument(id),
};

/**
 * The in-app protocol viewer.
 *
 * **Why the document no longer leaves the application.** #26 fetched the protocol as a blob through
 * HttpClient — necessary, because a browser-followed anchor carries no Bearer token — and then opened
 * it in a new tab as an object URL. That solved the 401 and left the reader in the browser's raw text
 * viewer: unstyled, outside the application, and on a shop-floor tablet or a kiosk terminal a place
 * that is genuinely hard to get back from. Rendering it here removes the category of problem instead
 * of working around it: nothing navigates, so nothing can navigate without a token, and the
 * synchronous `window.open` dance with its popup-blocker fallback is simply gone.
 *
 * The request itself is unchanged — the same `GET /api/protocols/{id}/document` through the same
 * interceptor. The backend was not touched.
 *
 * The download action is the one remaining use of a blob URL in the application, and it is the right
 * one: saving a file is exactly what an object URL is for.
 */
@Component({
  selector: 'app-protocol-dialog',
  imports: [AppDatePipe, ApprovalStateBadge, Dialog],
  templateUrl: './protocol-dialog.html',
  styleUrl: './protocol-dialog.css',
})
export class ProtocolDialog implements OnDestroy {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);
  // Injected rather than reached for as a global: it is the seam the download uses, and a test can
  // see what it did without writing a file.
  private readonly document = inject(DOCUMENT);

  protected readonly t = this.i18n.t;
  /** Handed down to the date pipe inside the badge, which is pure and cannot read a signal. */
  protected readonly language = this.i18n.language;

  /** The protocol to show, or `null` when the viewer is closed. Setting it starts the fetch. */
  readonly protocolId = input<string | null>(null);
  /**
   * Which endpoint to read the document from.
   *
   * The same file, fetched over a path with a different authorisation rule: `citation` is the
   * shop-floor path, open to the three roles that ask questions, and `moderation` is the admin one.
   * An admin holds no shop-floor role, so a viewer hard-wired to the first would 403 for exactly the
   * person this dialog was reused for.
   */
  readonly source = input<DocumentSource>('citation');
  /** What the answer called this protocol, so the dialog names what the reader clicked. */
  readonly protocolTitle = input('');
  /** The machine the question was asked about — the second half of "which document is this". */
  readonly machine = input('');
  /**
   * The protocol's approval, when the caller knows it. Null renders nothing.
   *
   * Optional because the three callers differ in what they hold: the Verwaltung row carries a full
   * {@link Approval}, a citation carries only a boolean widened into one, and the archive carries
   * none — approving something withdrawn from the corpus means nothing, and the backend refuses it.
   */
  readonly approval = input<Approval | null>(null);
  readonly closed = output<void>();

  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly parsed = signal<ProtocolDocument | null>(null);

  /**
   * What has been done to this protocol, or null when it was not asked for.
   *
   * <p><b>Only fetched on the moderation paths.</b> `citation` is the shop floor's viewer, and the
   * endpoint answers 403 for those roles by design — who corrected what and who took an approval
   * back names colleagues in connection with mistakes, which is moderation information. A technician
   * checking a citation gets the text and the approval STATE, both of which they already have.
   */
  protected readonly history = signal<ProtocolHistory | null>(null);

  /** Whether the reader may see a history at all. The same rule the endpoint enforces. */
  private readonly wantsHistory = computed(() => this.source() !== 'citation');

  /**
   * The events to render, or an empty list.
   *
   * <p>Already capped by the backend to {@link ProtocolHistory.limit}; this does not slice again.
   * Two places enforcing one cap is two places for it to drift, and the server's is the one that
   * bounds the payload.
   */
  protected readonly events = computed(() => this.history()?.events ?? []);

  /**
   * How many acts exist beyond the ones shown.
   *
   * <p>THE CAP IS A PRODUCT DECISION, NOT A TECHNICAL LIMIT (see `ProtocolModerationService`): the
   * viewer exists to let somebody read a protocol, and three lines answer "what happened to this
   * recently" without pushing the document below the fold. A full change history is a REPORT with
   * its own screen, deferred deliberately. This number is what stops the truncation being silent —
   * it is not an invitation to add a "show all" control here.
   */
  protected readonly olderEvents = computed(() => {
    const history = this.history();
    return history ? history.total - history.events.length : 0;
  });

  /**
   * Whether to fall back to the approval columns instead of a ledger.
   *
   * <p>The seeded case, and it is a true statement rather than a placeholder. The 150 protocols the
   * corpus was built from were approved by the V5 migration itself — `system:corpus-seed`, no human
   * act — so they have no ledger rows and never will. Showing nothing at all would imply the
   * approval came from somewhere it did not; showing the actor once, here, is the honest record of
   * the decision that seeded them.
   */
  protected readonly showsApprovalProvenance = computed(
    () =>
      this.wantsHistory() &&
      this.events().length === 0 &&
      (this.approval()?.approvedBy ?? null) !== null,
  );

  /** Nothing to say: no events, and no approval to attribute. An empty box would be worse. */
  protected readonly hasHistorySection = computed(
    () => this.events().length > 0 || this.showsApprovalProvenance(),
  );

  /** Held so the download saves the ORIGINAL bytes, not a re-encoding of the parsed text. */
  private readonly file = signal<{ blob: Blob; filename: string } | null>(null);

  /** Whether a download is possible — the bytes are here even when parsing gave up on them. */
  protected readonly canDownload = computed(() => this.file() !== null);

  private objectUrl: string | null = null;

  constructor() {
    effect(() => {
      const id = this.protocolId();
      if (id) {
        this.load(id);
      } else {
        this.reset();
      }
    });
  }

  ngOnDestroy(): void {
    this.releaseObjectUrl();
  }

  private load(protocolId: string): void {
    this.reset();
    this.loading.set(true);
    this.loadHistory(protocolId);

    const document$ = DOCUMENT_SOURCES[this.source()](this.api, protocolId);

    document$.subscribe({
      next: (response) => {
        this.loading.set(false);
        const blob = response.body;
        if (!blob) {
          this.failure.set('generic');
          return;
        }
        this.file.set({ blob, filename: filenameOf(response.headers.get('Content-Disposition')) });
        // `Blob.text()` decodes as UTF-8, which is what the backend sends and what the umlauts in
        // this corpus need.
        void blob.text().then((text) => this.parsed.set(parseProtocol(text)));
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.failure.set(classify(error));
      },
    });
  }

  /**
   * Fetches the ledger, and fails silently when it cannot.
   *
   * <p><b>A failure here leaves the history absent rather than putting an error in the dialog.</b>
   * The viewer's job is to show a protocol; the history is context beside it, and a red box about an
   * unreachable audit trail would sit next to a document that loaded perfectly and read as though
   * the document were the problem. The document's own failure has a message, because that one is the
   * dialog failing at what it is for.
   *
   * <p>Note the fallback below still runs: a protocol whose history call failed but whose approval
   * columns are present shows the provenance line, which is true whatever happened to this request.
   */
  private loadHistory(protocolId: string): void {
    if (!this.wantsHistory()) {
      return;
    }
    this.api.protocolHistory(protocolId).subscribe({
      next: (history) => this.history.set(history),
      error: () => this.history.set(null),
    });
  }

  /** The verb for one ledger action, so `UNAPPROVE` never reaches a screen. */
  protected actionLabel(action: ModerationEvent['action']): string {
    const labels = this.t().viewer;
    switch (action) {
      case 'EDIT':
        return labels.historyEdited;
      case 'APPROVE':
        return labels.historyApproved;
      case 'UNAPPROVE':
        return labels.historyUnapproved;
      default:
        return labels.historyDeleted;
    }
  }

  /**
   * Saves the original file.
   *
   * An object URL rather than a link to the API for the same reason the viewer exists: a download
   * the browser performs from an `/api/` href is a fresh navigation with no token on it.
   */
  protected download(): void {
    const file = this.file();
    if (!file) {
      return;
    }
    // The previous one is released here rather than after the click: revoking a URL the browser has
    // only just been pointed at is how a download silently produces an empty file.
    this.releaseObjectUrl();
    this.objectUrl = URL.createObjectURL(file.blob);

    const anchor = this.document.createElement('a');
    anchor.href = this.objectUrl;
    anchor.download = file.filename;
    anchor.click();
  }

  protected close(): void {
    this.closed.emit();
  }

  private reset(): void {
    this.loading.set(false);
    this.failure.set(null);
    this.parsed.set(null);
    this.file.set(null);
    // Cleared with the rest: a reviewer clicking from one protocol to the next must never read the
    // previous protocol's history under the new document, which is the failure mode of every
    // signal held across a reopen.
    this.history.set(null);
  }

  /**
   * An object URL pins its blob in memory until it is revoked, so a technician clicking through six
   * sources would otherwise leave six documents allocated for the life of the tab. At most one
   * download is live at a time, so holding exactly one and releasing it on the next is enough — and
   * it is not released on close, because a download the user started a moment ago may still be
   * reading it.
   */
  private releaseObjectUrl(): void {
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }
}
