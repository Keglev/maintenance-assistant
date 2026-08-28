import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Machine, UploadStatus } from '../../core/api/api.types';
import {
  ApiFailure,
  MaintenanceApiService,
  classify,
} from '../../core/api/maintenance-api.service';
import { AppDatePipe } from '../../core/i18n/app-date.pipe';
import { I18nService } from '../../core/i18n/i18n.service';
import { Pager } from '../../shared/pager/pager';

/** How the protocol got into the form: picked as a file, or typed here. */
type InputMode = 'file' | 'text';

/** Rows per page in "Meine Uploads" — the same five the two moderation tables use. */
const PAGE_SIZE = 5;

/**
 * The writers' upload view — the Techniker's and the Schichtleiter's.
 *
 * Write access belongs to two roles by decision (DECISIONS.txt: quality, consistency,
 * anti-garbage; then decision 3 of 2026-08-11, which added the Techniker), and the route is
 * guarded accordingly — but the guard is convenience. The backend refuses the upload for anyone
 * else, which is the check that counts (NFR-3). Correcting is not here and is not theirs: it lives
 * in the protocol view and belongs to the Schichtleiter alone.
 *
 * The view is built around the fact that upload answers **202, not 201**: the protocol exists but
 * is not searchable until a worker has chunked and embedded it. Hiding that behind a success
 * message would teach a writer that uploading and being findable are the same event, and the
 * first time indexing failed they would have no idea. So the status list is part of the view, and
 * it shows the failure reason rather than only the failure.
 *
 * **A protocol can be typed here as well as uploaded.** Requiring a `.txt` described a workflow that
 * does not exist: the writer types the protocol at the end of the shift, and nobody opens an
 * editor, saves a file and then picks it. Typing is the normal case and the file was the special one.
 *
 * The typed text is wrapped into a `File` in the browser and sent through the **same multipart
 * call**, which is why this is a frontend-only change: the endpoint, the ingestion pipeline, the
 * volume, the citation path and the download all stay unaware, and a typed protocol is
 * indistinguishable from an uploaded one the moment it is submitted. A backend "text" field would
 * have been a second write path to keep in step with the first, for no gain the user can see.
 */
@Component({
  selector: 'app-upload',
  imports: [AppDatePipe, FormsModule, Pager],
  templateUrl: './upload.html',
  styleUrl: './upload.css',
})
export class Upload {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  protected readonly machines = signal<Machine[]>([]);
  protected readonly uploads = signal<UploadStatus[]>([]);

  protected readonly mode = signal<InputMode>('file');
  protected readonly file = signal<File | null>(null);
  protected readonly text = signal('');
  protected readonly machineNo = signal('');
  protected readonly type = signal<'STOERUNG' | 'WARTUNG'>('STOERUNG');
  protected readonly title = signal('');
  protected readonly errorCode = signal('');

  protected readonly submitting = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly accepted = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);

  /** Passed to the date pipe, which cannot see a signal it is not given. */
  protected readonly language = this.i18n.language;

  /** Zero-based page of "Meine Uploads". */
  protected readonly page = signal(0);

  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.uploads().length / PAGE_SIZE)),
  );

  /**
   * The rows on screen.
   *
   * <p><b>Paged in the browser, and that is a deliberate limit rather than the right answer.</b>
   * {@code GET /api/protocols/mine} returns the caller's 50 most recent uploads in one response and
   * has no paging of its own; adding some would be a backend change, and this PR does not touch the
   * backend. Fifty rows is small enough that slicing them here costs nothing and reads the same to
   * the user. If that endpoint ever grows past its cap, this has to become a server-side page —
   * client-side paging of a truncated list would quietly page through the wrong fifty.
   */
  protected readonly pagedUploads = computed(() => {
    const start = this.page() * PAGE_SIZE;
    return this.uploads().slice(start, start + PAGE_SIZE);
  });

  /**
   * The native file input, so switching modes can actually clear it.
   *
   * Setting the `file` signal to null is not enough: the input keeps showing the chosen filename,
   * and the form would be telling the user one thing while holding another.
   */
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  /**
   * Whether there is something to submit.
   *
   * A machine, a title, and content in whichever mode is active. The title is required in both
   * modes because a protocol without one cannot be reviewed in any list — "Meine Uploads" would
   * show a machine number and a date, and the uploader would have to open each row to find out
   * which one is which.
   *
   * Deliberately shallow beyond that: size caps and rate limits belong to the upload-guards work
   * and are not smuggled in here — a validation rule that exists only in the browser is a
   * suggestion, and the place to make it a rule is the backend.
   */
  protected readonly canSubmit = computed(() => {
    if (!this.machineNo() || !this.title().trim()) {
      return false;
    }
    return this.mode() === 'file' ? this.file() !== null : this.text().trim().length > 0;
  });

  constructor() {
    this.api.machines().subscribe({ next: (machines) => this.machines.set(machines) });
    this.refresh();
  }

  protected pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
  }

  /**
   * Switches input mode and drops whatever the other mode was holding.
   *
   * Keeping it would mean a form that submits something the user cannot see — they typed a
   * protocol, switched to file to check something, switched back, and a stale file is still
   * attached. Cheaper to lose one input on an explicit mode switch than to submit a surprise.
   */
  protected useMode(mode: InputMode): void {
    if (this.mode() === mode) {
      return;
    }
    this.mode.set(mode);
    this.file.set(null);
    this.text.set('');
    this.clearFileInput();
  }

  protected submit(): void {
    if (!this.canSubmit() || this.submitting()) {
      return;
    }
    const file = this.mode() === 'file' ? this.file() : this.typedFile();
    if (!file) {
      return;
    }

    this.failure.set(null);
    this.accepted.set(false);
    this.submitting.set(true);

    const form = new FormData();
    form.append('file', file);
    // The upload endpoint takes the plant identifier, not the UUID: it resolves the machine itself.
    form.append('machine', this.machineNo());
    form.append('type', this.type());
    // No `language` part. Retrieval is language-agnostic by architecture — bge-m3 embeds DE and EN
    // into one space and the answer is pinned to the QUESTION's language — so the column never
    // reached a decision, and asking a writer to classify their own prose was a question
    // whose answer nothing read. The endpoint declares the parameter optional; see DECISIONS.txt.
    form.append('title', this.title().trim());
    if (this.errorCode().trim()) {
      form.append('errorCode', this.errorCode().trim());
    }

    this.api.upload(form).subscribe({
      next: () => {
        this.submitting.set(false);
        this.accepted.set(true);
        this.file.set(null);
        this.text.set('');
        this.clearFileInput();
        this.title.set('');
        this.errorCode.set('');
        // One refresh straight away, which will usually show RECEIVED. That is the honest state,
        // and seeing it is the point: the document is stored and not yet searchable.
        this.refresh();
      },
      error: (error: unknown) => {
        this.failure.set(classify(error));
        this.submitting.set(false);
      },
    });
  }

  /**
   * The typed protocol, as the file the upload endpoint already knows how to take.
   *
   * `text/plain` and the trimmed text: the ingestion pipeline reads UTF-8 text off the volume and
   * chunks it by paragraph, and that is exactly what this is. Nothing downstream can tell the
   * difference, which is the whole design.
   */
  private typedFile(): File {
    return new File([this.text().trim()], typedProtocolFilename(this.machineNo(), new Date()), {
      type: 'text/plain',
    });
  }

  private clearFileInput(): void {
    const input = this.fileInput()?.nativeElement;
    if (input) {
      input.value = '';
    }
  }

  /**
   * Reloads the status list on demand.
   *
   * Manual rather than polled. Indexing takes seconds, a poll would run for the whole time the tab
   * is open, and every one of those requests costs a database round trip to answer "still the
   * same". A button the user presses when they care is smaller, cheaper and easier to reason about
   * than a timer somebody has to remember to clear.
   */
  protected refresh(): void {
    this.refreshing.set(true);
    this.api.myUploads().subscribe({
      next: (uploads) => {
        this.uploads.set(uploads);
        // A refresh can shrink the list — a failed upload retried and removed, for instance — and
        // page 3 of a two-page list renders empty with no way back except the pager.
        this.page.update((current) =>
          Math.min(current, Math.max(0, Math.ceil(uploads.length / PAGE_SIZE) - 1)),
        );
        this.refreshing.set(false);
      },
      error: (error: unknown) => {
        this.failure.set(classify(error));
        this.refreshing.set(false);
      },
    });
  }

  protected statusLabel(status: UploadStatus['status']): string {
    const labels = this.t().upload;
    switch (status) {
      case 'INDEXED':
        return labels.statusIndexed;
      case 'FAILED':
        return labels.statusFailed;
      default:
        return labels.statusReceived;
    }
  }

  /** Which mode's panel to show — and, for the segmented control, which button is pressed. */
  protected isMode(mode: InputMode): boolean {
    return this.mode() === mode;
  }

  protected previousPage(): void {
    this.page.update((current) => Math.max(0, current - 1));
  }

  protected nextPage(): void {
    this.page.update((current) => Math.min(this.pageCount() - 1, current + 1));
  }
}

/**
 * The filename a typed protocol is stored and later downloaded under:
 * `<machineNo>-<yyyyMMdd-HHmmss>-eingabe.txt`.
 *
 * ASCII only, and not for tidiness: this name travels to the volume and comes back in
 * `Content-Disposition` when the viewer offers the download, and every layer in between has its own
 * opinion about non-ASCII filenames. The machine number is the useful half — an uploader
 * scanning the uploads list wants to see which press it was — and the timestamp to the second is
 * what keeps two protocols typed in the same shift apart.
 *
 * Local time rather than UTC, deliberately: the name is read by the person who typed it, and 22:14
 * means their shift, not a timezone conversion.
 */
export function typedProtocolFilename(machineNo: string, at: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const stamp =
    `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}` +
    `-${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
  // The seeded machine numbers are ASCII already; this is about what a future one might be, not
  // about what is in the corpus today.
  const machine = machineNo.replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'protokoll';
  return `${machine}-${stamp}-eingabe.txt`;
}
