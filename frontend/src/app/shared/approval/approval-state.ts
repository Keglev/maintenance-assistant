import { Component, computed, input } from '@angular/core';

import { Approval } from '../../core/api/api.types';
import { AppDatePipe } from '../../core/i18n/app-date.pipe';
import { UiLanguage } from '../../core/i18n/dictionary';
import { I18nService } from '../../core/i18n/i18n.service';
import { inject } from '@angular/core';

/**
 * Whether a protocol has been vouched for, said in one place so it is said the same way everywhere.
 *
 * <p><b>Why this is a component and not three copies of a span.</b> Decision 1 of 2026-08-11 makes
 * an unapproved protocol searchable and citable, and accepts the cost — it is less reliable to
 * troubleshoot from. What makes that trade honest is that the state is never hidden: it appears on
 * a source card under an answer, on a row in the Verwaltung table and in the viewer dialog. Three
 * hand-written markers would eventually disagree about the wording, and the one that quietly
 * dropped the "not approved" case is the one that turns the decision into a defect.
 *
 * <p><b>Colour is never the only signal.</b> Each state carries an icon AND a word, in both
 * palettes, which is the same rule the footer health dot and the two answer modes follow. A reader
 * with a colour vision deficiency, or one glancing at a tablet in plant lighting, gets the same
 * information as everyone else.
 *
 * <p><b>Approved is deliberately quiet, unapproved is deliberately not.</b> Most of the corpus is
 * approved, so a loud green badge on every row would be noise the eye learns to skip — and the
 * moment it does, the marker that matters stops being noticed too.
 */
@Component({
  selector: 'app-approval-state',
  imports: [AppDatePipe],
  templateUrl: './approval-state.html',
  styleUrl: './approval-state.css',
})
export class ApprovalStateBadge {
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  readonly approval = input.required<Approval>();

  /**
   * The language the date is formatted in.
   *
   * An input rather than something read from the service, because {@link AppDatePipe} is pure: a
   * language change alters no input on a table cell, so a pipe that read the signal itself would go
   * stale until something else happened to redraw the row.
   */
  readonly language = input.required<UiLanguage>();

  /**
   * Whether to name the approver and the day.
   *
   * Off on a source card under an answer — a technician reading an answer needs to know whether the
   * source was reviewed, not by whom — and on in the Verwaltung table and the viewer, where the
   * reader is an administrator auditing exactly that.
   */
  readonly showActor = input(false);

  protected readonly approved = computed(() => this.approval().state === 'APPROVED');
}
