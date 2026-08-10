import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Machine, UploadStatus } from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';

/** How the protocol got into the form: picked as a file, or typed here. */
export type InputMode = 'file' | 'text';

/**
 * The Schichtleiter's upload view.
 *
 * Write access belongs to one role by decision (DECISIONS.txt: quality, consistency,
 * anti-garbage), and the route is guarded accordingly — but the guard is convenience. The backend
 * refuses the upload for anyone else, which is the check that counts (NFR-3).
 *
 * The view is built around the fact that upload answers **202, not 201**: the protocol exists but
 * is not searchable until a worker has chunked and embedded it. Hiding that behind a success
 * message would teach a Schichtleiter that uploading and being findable are the same event, and the
 * first time indexing failed they would have no idea. So the status list is part of the view, and
 * it shows the failure reason rather than only the failure.
 *
 * **A protocol can be typed here as well as uploaded.** Requiring a `.txt` described a workflow that
 * does not exist: the Schichtleiter writes the protocol at the end of the shift, and nobody opens an
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
  imports: [FormsModule],
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
  protected readonly language = signal<'de' | 'en'>('de');

  protected readonly submitting = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly accepted = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);

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
   * Deliberately shallow: a machine, and content in whichever mode is active. Size caps and rate
   * limits belong to the upload-guards work and are not smuggled in here — a validation rule that
   * exists only in the browser is a suggestion, and the place to make it a rule is the backend.
   */
  protected readonly canSubmit = computed(() => {
    if (!this.machineNo()) {
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
    form.append('language', this.language());
    if (this.title().trim()) {
      form.append('title', this.title().trim());
    }
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

  /** Date only: the exact second an upload happened has never been the question. */
  protected shortDate(iso: string): string {
    const date = new Date(iso);
    return Number.isNaN(date.getTime())
      ? iso
      : date.toLocaleDateString(this.i18n.language(), {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
        });
  }
}

/**
 * The filename a typed protocol is stored and later downloaded under:
 * `<machineNo>-<yyyyMMdd-HHmmss>-eingabe.txt`.
 *
 * ASCII only, and not for tidiness: this name travels to the volume and comes back in
 * `Content-Disposition` when the viewer offers the download, and every layer in between has its own
 * opinion about non-ASCII filenames. The machine number is the useful half — a Schichtleiter
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
