import { Component, computed, input } from '@angular/core';

import { Approval } from '../../core/api/api.types';
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
 *
 * <p><b>IT SAYS THE STATE AND NOTHING ELSE (2026-08-14).</b> It used to name the approver and the
 * day behind a {@code showActor} flag, and the flag is deleted rather than defaulted off: the
 * longest form of it — "Freigegeben system:corpus-seed · 12.08.2026" — truncated in the Verwaltung
 * table in production and ran under the action buttons. Who and when are PROVENANCE, and provenance
 * belongs in the record rather than repeated in every list that mentions it. It is in the protocol
 * viewer's history section now. The {@code language} input went with it: with no date to format,
 * a required input that every one of four call sites had to supply was a cost with no purchase.
 */
@Component({
  selector: 'app-approval-state',
  templateUrl: './approval-state.html',
  styleUrl: './approval-state.css',
})
export class ApprovalStateBadge {
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  readonly approval = input.required<Approval>();

  protected readonly approved = computed(() => this.approval().state === 'APPROVED');
}
