import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Machine, ModeratedProtocol, NO_FILTER, ProtocolFilter } from '../../core/api/api.types';
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
  imports: [Dialog, FormsModule, ProtocolDialog],
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

  /** The machine dropdown's options. Readable by an admin since the ADR-006 role gained a job. */
  protected readonly machines = signal<Machine[]>([]);
  protected readonly machinesFailed = signal(false);

  /** What the form currently holds — edited freely, and not yet asked for. */
  protected readonly draft = signal<ProtocolFilter>(NO_FILTER);
  /** What the list on screen is actually narrowed to. */
  protected readonly applied = signal<ProtocolFilter>(NO_FILTER);

  /**
   * Whether the title and date fields are usable.
   *
   * The owner's rule, and the backend's: the machine comes first. Across ten machines a title
   * fragment on its own answers with rows from machines the reviewer was not looking at. Disabling
   * the fields says so before the request rather than after it — the 400 stays as a backstop for a
   * caller that is not this form.
   */
  protected readonly machineChosen = computed(() => this.draft().machineNo !== '');

  /** Whether the list on screen is narrowed, which decides which "nothing here" sentence it gets. */
  protected readonly isFiltered = computed(() =>
    Object.values(this.applied()).some((value) => value !== ''),
  );

  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.total() / PAGE_SIZE)),
  );
  protected readonly hasPrevious = computed(() => this.page() > 0);
  protected readonly hasNext = computed(() => this.page() + 1 < this.pageCount());

  constructor() {
    this.load(0);
    this.api.machines().subscribe({
      next: (machines) => this.machines.set(machines),
      // Not fatal: the corpus list is the view, and the filter is the extra. A failed machine call
      // costs the dropdown, not the page.
      error: () => this.machinesFailed.set(true),
    });
  }

  /** Edits one field of the form. */
  protected edit(field: keyof ProtocolFilter, value: string): void {
    const next = { ...this.draft(), [field]: value };
    if (field === 'machineNo' && value === '') {
      // Clearing the machine clears what depended on it. Leaving a title behind in a field the user
      // can no longer see or reach is how a filter starts lying about what it is filtering.
      this.draft.set(NO_FILTER);
      return;
    }
    this.draft.set(next);
  }

  /** Applies the form. Back to page 0: page 3 of the old result is not page 3 of the new one. */
  protected applyFilter(): void {
    this.applied.set(this.draft());
    this.load(0);
  }

  /** Clears the form and the list back to the whole corpus. */
  protected resetFilter(): void {
    this.draft.set(NO_FILTER);
    this.applied.set(NO_FILTER);
    this.load(0);
  }

  protected load(page: number): void {
    this.loading.set(true);
    this.failure.set(null);
    this.api.moderationProtocols(page, PAGE_SIZE, this.applied()).subscribe({
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
