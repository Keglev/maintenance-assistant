import { Component, computed, inject, signal } from '@angular/core';

import { ModeratedProtocol } from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Dialog } from '../../shared/dialog/dialog';
import { ProtocolDialog } from '../../shared/protocol/protocol-dialog';

/** Rows per page. Ten fits a tablet without scrolling the header off. */
const PAGE_SIZE = 10;

/**
 * The administrator's view of the corpus: everything that is in it, and the power to remove any of
 * it.
 *
 * <p>The admin role has had no shop-floor function until now — it administered Keycloak and saw an
 * empty application. This is its function, and ADR-006 is why it exists: the write path is
 * restricted for quality, which bounds volume and profanity but not a *plausible* protocol with a
 * wrong Massnahme, filed by someone entitled to file it. Mode A cites that faithfully.
 *
 * <p>There is deliberately no edit. Correcting a protocol is delete-then-reupload by the
 * Schichtleiter, so that no answer can cite text that changed underneath it — the view says so
 * rather than leaving the absence to be read as an oversight.
 */
@Component({
  selector: 'app-moderation',
  imports: [Dialog, ProtocolDialog],
  templateUrl: './moderation.html',
  styleUrl: './moderation.css',
})
export class Moderation {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  protected readonly protocols = signal<readonly ModeratedProtocol[]>([]);
  protected readonly page = signal(0);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);

  /** The protocol open in the viewer, or null. */
  protected readonly viewing = signal<ModeratedProtocol | null>(null);
  /** The protocol whose deletion is being confirmed, or null. */
  protected readonly deleting = signal<ModeratedProtocol | null>(null);
  /** What was just removed, so the notice can name it. */
  protected readonly removed = signal<string | null>(null);

  protected readonly pageSize = PAGE_SIZE;

  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.total() / PAGE_SIZE)),
  );
  protected readonly hasPrevious = computed(() => this.page() > 0);
  protected readonly hasNext = computed(() => this.page() + 1 < this.pageCount());

  constructor() {
    this.load(0);
  }

  protected load(page: number): void {
    this.loading.set(true);
    this.failure.set(null);
    this.api.moderationProtocols(page, PAGE_SIZE).subscribe({
      next: (result) => {
        this.protocols.set(result.items);
        this.page.set(result.page);
        this.total.set(result.total);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.failure.set(classify(error));
        this.loading.set(false);
      },
    });
  }

  protected previous(): void {
    if (this.hasPrevious()) {
      this.load(this.page() - 1);
    }
  }

  protected next(): void {
    if (this.hasNext()) {
      this.load(this.page() + 1);
    }
  }

  /**
   * Removes the protocol whose deletion was confirmed.
   *
   * The list is reloaded rather than spliced: a delete changes the total, and therefore which rows
   * belong on this page. Removing the row locally would leave a page of nine and a pagination
   * footer quietly describing a corpus that no longer exists.
   */
  protected confirmDelete(): void {
    const target = this.deleting();
    if (!target) {
      return;
    }
    this.deleting.set(null);
    this.failure.set(null);

    this.api.deleteProtocol(target.id).subscribe({
      next: () => {
        this.removed.set(target.title);
        // Stepping back a page when the last row of the last page goes, so a confirmed delete never
        // lands the reviewer on an empty page they have to navigate out of.
        const wasLastRow = this.protocols().length === 1 && this.page() > 0;
        this.load(wasLastRow ? this.page() - 1 : this.page());
      },
      error: (error: unknown) => this.failure.set(classify(error)),
    });
  }

  /** Date only: which day a protocol was filed is the question, not which second. */
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

  protected statusLabel(status: ModeratedProtocol['status']): string {
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
}
