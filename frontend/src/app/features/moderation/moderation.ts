import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  ArchivedProtocol,
  Machine,
  ModeratedProtocol,
  NO_FILTER,
  ProtocolFilter,
  SimilarProtocol,
  SimilarityReport,
} from '../../core/api/api.types';
import {
  ApiFailure,
  MaintenanceApiService,
  classify,
} from '../../core/api/maintenance-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { AppDatePipe } from '../../core/i18n/app-date.pipe';
import { ApprovalStateBadge } from '../../shared/approval/approval-state';
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
 *
 * <p><b>v1.2 splits the jobs.</b> Approval is the administrator's, correction is the Schichtleiter's,
 * and nobody holds both — so the row's buttons now depend on the reader's role rather than on the
 * view they are standing in. See {@link canCorrect} and {@link canApprove} for what that means here
 * and for the routing question it exposes.
 *
 * <p><b>Duplicate detection, 2026-08-14, and the one rule it follows: it WARNS, it never blocks.</b>
 * Approving now asks first whether this machine already holds a protocol saying close to the same
 * thing, and shows up to three if it does — with their dates, their similarity and, decisively,
 * their OWN approval state. Nothing on this screen can refuse an approval on that basis, the
 * approve button in the dialog is never disabled, and a failed similarity call approves anyway
 * ({@link approve}). Four legitimate E-47 protocols on PR-03 describe four different root causes
 * behind one fault code, and a feature that could turn "similar" into "refused" would have removed
 * exactly the knowledge that makes the demo answer good.
 */
@Component({
  selector: 'app-moderation',
  imports: [AppDatePipe, ApprovalStateBadge, Dialog, FormsModule, Pager, ProtocolDialog],
  templateUrl: './moderation.html',
  styleUrl: './moderation.css',
})
export class Moderation {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);

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

  /**
   * Who may CORRECT a protocol: the Schichtleiter, and since 2026-08-13 nobody else.
   *
   * <p><b>The administrator lost this button, deliberately.</b> Carlos's decision after reviewing
   * #53: Techniker writes, Schichtleiter corrects, Admin approves, and nobody does two jobs. The
   * backend refuses an admin's `PUT` with a 403, so leaving the button on screen would offer an
   * action that cannot succeed — and a button that 403s is worse than an absent one, because the
   * reader concludes the system is broken rather than that the job is not theirs.
   *
   * <p><b>A FINDING TRAVELS WITH THIS, and it is not fixed here.</b> `/moderation` is guarded by
   * `roleGuard('admin')`, so a Schichtleiter cannot reach this view at all — which means the
   * correction path now has no interface, for anyone. Widening the route is a permission decision
   * that belongs to Carlos, not to this pull request, so the rule is written correctly here and the
   * gap is reported. The day the route opens, this button is already right.
   */
  protected readonly canCorrect = computed(() => this.auth.realmRoles().includes('schichtleiter'));

  /** Who may approve. Admin only, here and — decisively — on the endpoint. */
  protected readonly canApprove = computed(() => this.auth.realmRoles().includes('admin'));

  /**
   * Who may remove a protocol from the corpus, and read the archive of what was removed.
   *
   * <p>The administrator, and nobody else — unchanged by 2026-08-14's widening. That change opened
   * the corpus LIST and the DOCUMENT read to the Schichtleiter so their one write would be
   * reachable; it opened nothing else, and this signal is where the interface says so.
   *
   * <p><b>Absent rather than disabled.</b> A Löschen button that exists only to refuse is noise on a
   * shop-floor tablet, and worse, it tells the reader their role includes a power it does not.
   */
  protected readonly canArchive = this.canApprove;

  /**
   * What this screen is called, which depends on what the reader may do on it.
   *
   * <p>"Protokollverwaltung" describes reviewing and removing. A Schichtleiter does neither: they
   * correct. Two roles behind one route need two names, or the wording quietly contradicts the
   * buttons — which is the same defect as a control that only refuses, arriving through the door of
   * the page title.
   */
  protected readonly heading = computed(() =>
    this.canApprove() ? this.t().moderation.heading : this.t().moderation.headingCorrector,
  );

  protected readonly intro = computed(() =>
    this.canApprove() ? this.t().moderation.intro : this.t().moderation.introCorrector,
  );

  /** The protocol whose approval is being changed, while the request is in flight. */
  protected readonly approving = signal<string | null>(null);
  /** The protocol whose approval is being withdrawn, or null. Withdrawal needs a reason. */
  protected readonly withdrawing = signal<ModeratedProtocol | null>(null);
  protected readonly withdrawComment = signal('');
  /** What was just approved or un-approved, so the notice can name it. */
  protected readonly approvalNotice = signal<{ title: string; approved: boolean } | null>(null);
  /**
   * Why an approval was refused, and for which protocol.
   *
   * Held per row rather than as one page-level error: the reader clicked one button among ten, and
   * a sentence at the top of the page about "this protocol" names none of them.
   */
  protected readonly approvalFailure = signal<{ id: string; reason: ApiFailure } | null>(null);

  // -------------------------------------------------------------------------------------------
  // Duplicate detection — INFORMATION, never a gate
  // -------------------------------------------------------------------------------------------

  /** Which protocol's similarity check is in flight, so its button can say it is working. */
  protected readonly checkingDuplicates = signal<string | null>(null);
  /** The protocol about to be approved, held while its similar siblings are on screen. */
  protected readonly duplicatesOf = signal<ModeratedProtocol | null>(null);
  /** What the check found. Null means no dialog — there is nothing to compare. */
  protected readonly duplicates = signal<SimilarityReport | null>(null);
  /** A candidate open in the read-only viewer, or null. */
  protected readonly viewingCandidate = signal<SimilarProtocol | null>(null);

  /**
   * Whether the duplicate list is on screen.
   *
   * <p>It steps aside while a candidate is open in the viewer instead of stacking two modals: two
   * focus traps in one page fight over Tab and Escape, and the alternative — closing this dialog to
   * open the viewer — would throw away the list the reviewer is in the middle of comparing. Closing
   * the viewer brings it straight back, with its state intact.
   */
  protected readonly duplicatesOpen = computed(
    () => this.duplicates() !== null && this.viewingCandidate() === null,
  );

  /** The threshold as a whole percentage, for the one sentence that explains the numbers. */
  protected readonly duplicateThresholdPercent = computed(() =>
    Math.round((this.duplicates()?.threshold ?? 0) * 100),
  );

  /** How many cleared the threshold beyond the three shown. Zero renders nothing. */
  protected readonly duplicatesBeyondShown = computed(() => {
    const report = this.duplicates();
    return report ? report.total - report.candidates.length : 0;
  });

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

  /** The backend refuses a withdrawal without a reason; so does this, before the round trip. */
  protected readonly canWithdraw = computed(() => this.withdrawComment().trim() !== '');

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

  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.total() / PAGE_SIZE)));
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
      //
      // THE APPROVAL FILTER SURVIVES, because it never depended on the machine: it is the one filter
      // the backend applies without one, for the reason a queue is only useful whole. Resetting it
      // here would mean "all machines" silently emptied the reviewer's queue — a filter clearing a
      // filter it has nothing to do with.
      this.draft.set({ ...NO_FILTER, approvalState: this.draft().approvalState });
      return;
    }
    this.draft.set(next);
  }

  /**
   * Applies the approval filter immediately, without the Filter button.
   *
   * It is not part of the machine-first form: it needs no machine, it has one value, and a queue a
   * reviewer has to press a second control to see is a queue they will stop opening. The other four
   * fields keep the button, because they are a form that is filled in and then submitted.
   */
  protected filterByApproval(state: ProtocolFilter['approvalState']): void {
    this.draft.update((current) => ({ ...current, approvalState: state }));
    this.applied.set(this.draft());
    this.load(0);
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
  // Approval
  // -------------------------------------------------------------------------------------------

  /**
   * Starts an approval by asking what the corpus already says.
   *
   * <p><b>The dialog appears only when there is something to look at.</b> If nothing on this machine
   * is similar, this is still the #54 behaviour — one click, no confirmation, because approving
   * affirms text the reader has just read. A dialog that opened every time to announce "nothing
   * found" would tax the common case to serve the rare one, and a permanently empty section is how a
   * reader learns to click straight past a section that will one day be full.
   *
   * <p><b>It fails OPEN, deliberately.</b> A failed similarity call approves anyway rather than
   * stopping the reviewer, and nothing is lost by it: the backend recomputes the same comparison
   * inside the approval and writes what it found into the ledger, so the audit record is correct
   * whether or not this call succeeded. The check here is an aid to a decision; it is not the
   * decision, and it is not the record. Blocking an approval on a failed *warning* would invert the
   * governing rule of the whole feature.
   */
  protected approve(protocol: ModeratedProtocol): void {
    if (this.approving() || this.checkingDuplicates()) {
      return;
    }
    this.checkingDuplicates.set(protocol.id);
    this.approvalFailure.set(null);

    this.api.similarProtocols(protocol.id).subscribe({
      next: (report) => {
        this.checkingDuplicates.set(null);
        if (report.candidates.length === 0) {
          this.commitApproval(protocol);
          return;
        }
        this.duplicatesOf.set(protocol);
        this.duplicates.set(report);
      },
      error: () => {
        this.checkingDuplicates.set(null);
        this.commitApproval(protocol);
      },
    });
  }

  /** Approves the protocol whose similar siblings the reviewer has just been shown. */
  protected approveFromDialog(): void {
    const target = this.duplicatesOf();
    if (!target) {
      return;
    }
    this.closeDuplicates();
    this.commitApproval(target);
  }

  /**
   * A cosine similarity as a whole percentage.
   *
   * <p>Rounded to no decimals deliberately: 93.05 % beside 92.87 % invites a reviewer to treat the
   * difference as meaningful, and it is not — the decision this number informs is "open both and
   * read them", which 93 % and 93 % answer identically.
   */
  protected percent(similarity: number): number {
    return Math.round(similarity * 100);
  }

  protected closeDuplicates(): void {
    this.duplicatesOf.set(null);
    this.duplicates.set(null);
    this.viewingCandidate.set(null);
  }

  /**
   * Opens one candidate in the read-only viewer.
   *
   * <p>The duplicate dialog steps aside rather than stacking a second modal on top of itself — see
   * {@link duplicatesOpen}. Two focus traps in one page is a bug looking for a keyboard, and closing
   * this one would lose the list the reviewer is halfway through comparing. It comes back when the
   * viewer does.
   */
  protected openCandidate(candidate: SimilarProtocol): void {
    this.viewingCandidate.set(candidate);
  }

  /**
   * Performs the approval itself.
   *
   * <p>A failure is still reported beside the row it belongs to rather than at the top of the page,
   * because the reader pressed one button among ten identical ones. What can fail is now narrower:
   * the protocol was archived or removed by somebody else while this screen was open. The
   * four-eyes refusal that used to be handled here is gone with the runtime check behind it
   * (2026-08-15) — the property is carried by the role split and guarded by a test, and no response
   * this client can receive says `FOUR_EYES_REQUIRED` any more.
   */
  private commitApproval(protocol: ModeratedProtocol): void {
    this.approving.set(protocol.id);

    this.api.setApproval(protocol.id, true).subscribe({
      next: () => {
        this.approving.set(null);
        this.approvalNotice.set({ title: protocol.title, approved: true });
        // Reloaded rather than patched in place: the row may no longer belong on this page at all
        // when the approval queue is the active filter, and a row that stayed would be describing a
        // list it has just left.
        this.load(this.page());
      },
      error: (error: unknown) => {
        this.approving.set(null);
        this.approvalFailure.set({ id: protocol.id, reason: classify(error) });
      },
    });
  }

  /**
   * Opens the withdrawal dialog on a protocol, carrying a reason over only when it belongs there.
   *
   * <p><b>Both entry points go through here — the row button and the viewer — and neither may set
   * `withdrawing` directly again.</b> That is the whole fix: the reason is component state, so a
   * dialog opened by assignment inherits whatever the last attempt left behind.
   *
   * <p>THE TWO HALVES ARE SEPARABLE, and only one of them was ever wanted. RETRYING THE SAME
   * PROTOCOL KEEPS THE SENTENCE: a network blip must not cost a reviewer the paragraph they just
   * wrote, and asking them to type it again is how a withdrawal ends up with a thinner reason than
   * the one it deserved. OPENING ANOTHER PROTOCOL STARTS BLANK: the reason is written into the
   * moderation ledger and attributed as if meant about that protocol, so a sentence left in the box
   * is one click from being permanently attached to a protocol nobody wrote it about. Convenience on
   * one side, an unfixable record on the other.
   *
   * <p>`approvalFailure` is the evidence of which protocol the retained reason belongs to — it
   * already carries the failed target's id, for the refusal shown beside that row. A second signal
   * tracking the same fact could disagree with it, and the two would then be right about different
   * protocols.
   */
  protected beginWithdraw(protocol: ModeratedProtocol): void {
    if (this.approvalFailure()?.id !== protocol.id) {
      this.withdrawComment.set('');
    }
    this.withdrawing.set(protocol);
  }

  /** Withdraws approval, with the reason the backend requires and the reader is owed. */
  protected confirmWithdraw(): void {
    const target = this.withdrawing();
    const comment = this.withdrawComment().trim();
    if (!target || !comment || this.approving()) {
      return;
    }
    this.approving.set(target.id);
    this.approvalFailure.set(null);

    this.api.setApproval(target.id, false, comment).subscribe({
      next: () => {
        this.approving.set(null);
        this.withdrawing.set(null);
        this.withdrawComment.set('');
        this.approvalNotice.set({ title: target.title, approved: false });
        this.load(this.page());
      },
      error: (error: unknown) => {
        this.approving.set(null);
        this.withdrawing.set(null);
        this.approvalFailure.set({ id: target.id, reason: classify(error) });
      },
    });
  }

  protected cancelWithdraw(): void {
    this.withdrawing.set(null);
    this.withdrawComment.set('');
  }

  /** The refusal for THIS row, or null — so a sentence never appears beside the wrong protocol. */
  protected approvalFailureFor(protocol: ModeratedProtocol): ApiFailure | null {
    const failure = this.approvalFailure();
    return failure && failure.id === protocol.id ? failure.reason : null;
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
