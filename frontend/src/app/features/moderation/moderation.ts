import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  ArchivedProtocol,
  Machine,
  ModeratedProtocol,
  NO_FILTER,
  ProtocolFilter,
} from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { AppDatePipe } from '../../core/i18n/app-date.pipe';
import { Dialog } from '../../shared/dialog/dialog';
import { Pager } from '../../shared/pager/pager';
import { ProtocolDialog } from '../../shared/protocol/protocol-dialog';

/**
 * Rows per page.
 *
 * Five, not ten: on a tablet held upright ten rows push the pager below the fold, so the control
 * that says there is more is only reachable by scrolling past everything it is offering. Five rows
 * and a pager both fit.
 */
const PAGE_SIZE = 5;

/**
 * The administrator's view of the corpus: everything that is in it, and the power to remove any of
 * it.
 *
 * <p>The admin role has had no shop-floor function until now — it administered Keycloak and saw an
 * empty application. This is its function, and ADR-006 is why it exists: the write path is
 * restricted for quality, which bounds volume and profanity but not a *plausible* protocol with a
 * wrong Massnahme, filed by someone entitled to file it. Mode A cites that faithfully.
 *
 * <p><b>Two things changed with ADR-006's 2026-08-10 revision.</b> A protocol can be corrected in
 * place — the old rule against it assumed stored answers, and this system generates every answer per
 * query — and a deletion no longer destroys the protocol, it archives it. Both require a stated
 * reason, and neither can be undone: there is no restore, and the edit dialog will not let anyone
 * move a protocol to another machine, because machine identity is provenance rather than content.
 */
@Component({
  selector: 'app-moderation',
  imports: [AppDatePipe, Dialog, FormsModule, Pager, ProtocolDialog],
  templateUrl: './moderation.html',
  styleUrl: './moderation.css',
})
export class Moderation {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;
  /** Passed to the date pipe, which cannot see a signal it is not given. */
  protected readonly language = this.i18n.language;

  protected readonly protocols = signal<readonly ModeratedProtocol[]>([]);
  protected readonly page = signal(0);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);

  /** The protocol open in the viewer, or null. */
  protected readonly viewing = signal<ModeratedProtocol | null>(null);
  /** The archived protocol open in the viewer, or null — a different endpoint, so a different slot. */
  protected readonly viewingArchived = signal<ArchivedProtocol | null>(null);
  /** The protocol whose deletion is being confirmed, or null. */
  protected readonly deleting = signal<ModeratedProtocol | null>(null);
  /** Why it is being deleted. Required, here as well as on the server. */
  protected readonly deleteComment = signal('');
  /** What was just removed, so the notice can name it. */
  protected readonly removed = signal<string | null>(null);
  /** What was just corrected, so the notice can say it is being re-indexed. */
  protected readonly corrected = signal<string | null>(null);

  /** Which half of the view is on screen. */
  protected readonly tab = signal<'corpus' | 'archive'>('corpus');

  /** The protocol being corrected, or null. */
  protected readonly editing = signal<ModeratedProtocol | null>(null);
  protected readonly editTitle = signal('');
  protected readonly editErrorCode = signal('');
  protected readonly editContent = signal('');
  protected readonly editComment = signal('');
  /** The document is fetched when the dialog opens; until then there is nothing to correct. */
  protected readonly editLoading = signal(false);
  protected readonly editFailure = signal<ApiFailure | null>(null);
  protected readonly editSaving = signal(false);

  /** The archive, which is its own list with its own paging. */
  protected readonly archived = signal<readonly ArchivedProtocol[]>([]);
  protected readonly archivePage = signal(0);
  protected readonly archiveTotal = signal(0);
  protected readonly archiveCap = signal(0);
  protected readonly archiveMachine = signal('');
  protected readonly archiveLoading = signal(false);

  /**
   * Whether the correction can be submitted.
   *
   * The same three rules the backend enforces, checked here so the reader is told before the round
   * trip rather than by a 400 afterwards. The backend keeps them regardless — a rule that exists
   * only in the browser is a suggestion.
   */
  protected readonly canSaveEdit = computed(
    () =>
      this.editTitle().trim() !== '' &&
      this.editContent().trim() !== '' &&
      this.editComment().trim() !== '',
  );

  protected readonly canDelete = computed(() => this.deleteComment().trim() !== '');

  protected readonly archivePageCount = computed(() =>
    Math.max(1, Math.ceil(this.archiveTotal() / PAGE_SIZE)),
  );
  protected readonly hasPreviousArchive = computed(() => this.archivePage() > 0);
  protected readonly hasNextArchive = computed(
    () => this.archivePage() + 1 < this.archivePageCount(),
  );

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
    const comment = this.deleteComment().trim();
    if (!target || !comment) {
      return;
    }
    this.deleting.set(null);
    this.failure.set(null);

    this.api.deleteProtocol(target.id, comment).subscribe({
      next: () => {
        this.removed.set(target.title);
        this.deleteComment.set('');
        // Stepping back a page when the last row of the last page goes, so a confirmed delete never
        // lands the reviewer on an empty page they have to navigate out of.
        const wasLastRow = this.protocols().length === 1 && this.page() > 0;
        this.load(wasLastRow ? this.page() - 1 : this.page());
      },
      error: (error: unknown) => this.failure.set(classify(error)),
    });
  }

  protected cancelDelete(): void {
    this.deleting.set(null);
    this.deleteComment.set('');
  }

  // -------------------------------------------------------------------------------------------
  // Correction
  // -------------------------------------------------------------------------------------------

  /**
   * Opens the edit dialog and fetches the text to correct.
   *
   * The current document is loaded rather than reconstructed from the row: `title` and `errorCode`
   * live on the protocol, but the thing being corrected is the document on the volume, and an edit
   * form pre-filled with anything else would silently replace the text with whatever the form
   * happened to know.
   */
  protected startEdit(protocol: ModeratedProtocol): void {
    this.editing.set(protocol);
    this.editTitle.set(protocol.title);
    this.editErrorCode.set(protocol.errorCode ?? '');
    this.editContent.set('');
    this.editComment.set('');
    this.editFailure.set(null);
    this.editLoading.set(true);

    this.api.getModerationDocument(protocol.id).subscribe({
      next: (response) => {
        this.editLoading.set(false);
        // `Blob.text()` decodes as UTF-8, which is what the backend sends and what this corpus
        // needs — a protocol full of umlauts read as Latin-1 would be corrupted by the correction
        // that was meant to fix it.
        void response.body?.text().then((text) => this.editContent.set(text));
      },
      error: (error: unknown) => {
        this.editLoading.set(false);
        this.editFailure.set(classify(error));
      },
    });
  }

  protected cancelEdit(): void {
    this.editing.set(null);
    this.editSaving.set(false);
  }

  /**
   * Submits the correction.
   *
   * Machine and type are sent back exactly as they arrived. The dialog shows them read-only and the
   * backend refuses a change, so this is an echo rather than an input — but it is sent, because a
   * request that omitted them would be a request that could be built by a client that thought they
   * were optional.
   */
  protected saveEdit(): void {
    const target = this.editing();
    if (!target || !this.canSaveEdit() || this.editSaving()) {
      return;
    }
    this.editSaving.set(true);
    this.editFailure.set(null);

    this.api
      .editProtocol(target.id, {
        machineNo: target.machineNo,
        protocolType: target.protocolType,
        title: this.editTitle().trim(),
        errorCode: this.editErrorCode().trim(),
        content: this.editContent(),
        comment: this.editComment().trim(),
      })
      .subscribe({
        next: () => {
          this.editSaving.set(false);
          this.editing.set(null);
          // 202, not 200: the text is corrected and the search index is not, for a few seconds.
          this.corrected.set(target.title);
          this.load(this.page());
        },
        error: (error: unknown) => {
          this.editSaving.set(false);
          this.editFailure.set(classify(error));
        },
      });
  }

  // -------------------------------------------------------------------------------------------
  // The archive
  // -------------------------------------------------------------------------------------------

  protected showTab(tab: 'corpus' | 'archive'): void {
    this.tab.set(tab);
    if (tab === 'archive') {
      // Loaded on arrival rather than at construction: an administrator who never opens the archive
      // should not pay for it on every visit to the corpus.
      this.loadArchive(0);
    }
  }

  protected loadArchive(page: number): void {
    this.archiveLoading.set(true);
    this.failure.set(null);
    this.api.deletedProtocols(this.archiveMachine(), page, PAGE_SIZE).subscribe({
      next: (result) => {
        this.archived.set(result.items);
        this.archivePage.set(result.page);
        this.archiveTotal.set(result.total);
        // The cap comes from the backend so the hint on screen cannot drift away from the number
        // the purge actually enforces.
        this.archiveCap.set(result.cap);
        this.archiveLoading.set(false);
      },
      error: (error: unknown) => {
        this.failure.set(classify(error));
        this.archiveLoading.set(false);
      },
    });
  }

  protected filterArchive(machineNo: string): void {
    this.archiveMachine.set(machineNo);
    this.loadArchive(0);
  }

  protected previousArchive(): void {
    if (this.hasPreviousArchive()) {
      this.loadArchive(this.archivePage() - 1);
    }
  }

  protected nextArchive(): void {
    if (this.hasNextArchive()) {
      this.loadArchive(this.archivePage() + 1);
    }
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
