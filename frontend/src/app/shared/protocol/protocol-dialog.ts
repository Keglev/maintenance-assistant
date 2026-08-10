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

import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Dialog } from '../dialog/dialog';
import { ProtocolDocument, filenameOf, parseProtocol } from './protocol-document';

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
  imports: [Dialog],
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
  readonly source = input<'citation' | 'moderation'>('citation');
  /** What the answer called this protocol, so the dialog names what the reader clicked. */
  readonly protocolTitle = input('');
  /** The machine the question was asked about — the second half of "which document is this". */
  readonly machine = input('');
  readonly closed = output<void>();

  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly parsed = signal<ProtocolDocument | null>(null);

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

    const document$ =
      this.source() === 'moderation'
        ? this.api.getModerationDocument(protocolId)
        : this.api.getDocument(protocolId);

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
