import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Machine, UploadStatus } from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';

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

  protected readonly file = signal<File | null>(null);
  protected readonly machineNo = signal('');
  protected readonly type = signal<'STOERUNG' | 'WARTUNG'>('STOERUNG');
  protected readonly title = signal('');
  protected readonly errorCode = signal('');
  protected readonly language = signal<'de' | 'en'>('de');

  protected readonly submitting = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly accepted = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);

  constructor() {
    this.api.machines().subscribe({ next: (machines) => this.machines.set(machines) });
    this.refresh();
  }

  protected pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
  }

  protected submit(): void {
    const file = this.file();
    if (!file || !this.machineNo() || this.submitting()) {
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
