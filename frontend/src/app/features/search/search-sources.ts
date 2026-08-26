import { DOCUMENT } from '@angular/common';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';

import { Approval, Citation } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { ApprovalStateBadge } from '../../shared/approval/approval-state';

/**
 * How many sources fit under an answer before the list starts hiding it.
 *
 * Three or more is where the answer stops being the first thing on screen after a scroll on a
 * tablet. Two is a pair of cards and no reason for a control.
 */
const COLLAPSE_ABOVE = 2;

/**
 * One request to reveal a source, as an object rather than an id.
 *
 * The identity is the point: two clicks on the same marker are two events, and a signal holding a
 * bare string would only notify on the first.
 */
export interface SourceFocus {
  readonly protocolId: string;
}

/** Stable per protocol, so a citation marker can find its card without an index that shifts. */
export function sourceCardId(citation: Citation): string {
  return `source-${citation.protocolId}`;
}

/**
 * The Mode A source list.
 *
 * <p><b>The answer above it is never collapsed or paged, and that is the rule this component exists
 * to protect.</b> Citation markers flow through the prose; splitting the text would separate a claim
 * from the source it names, which is exactly the Mode A guarantee (NFR-2). What can be collapsed is
 * the list underneath — and above two sources it has to be, because five expanded cards push the
 * answer off a tablet screen.
 *
 * <p>Its own component because the search view was carrying the markup, the expand state and a third
 * of its own stylesheet for something that is not about asking questions. Extracting it also put
 * `search.css` back inside the per-component style budget.
 */
@Component({
  selector: 'app-search-sources',
  imports: [ApprovalStateBadge],
  templateUrl: './search-sources.html',
  styleUrl: './search-sources.css',
})
export class SearchSources {
  private readonly i18n = inject(I18nService);
  // Injected rather than reached for as a global: scrolling to a card is a DOM act, and a test
  // should be able to see it happen without a browser.
  private readonly document = inject(DOCUMENT);

  protected readonly t = this.i18n.t;
  /** Handed to the badge, which hands it to the date pipe — a pure pipe cannot read a signal. */
  protected readonly language = this.i18n.language;

  readonly citations = input.required<readonly Citation[]>();
  /** What to call the machine on a card read on its own. Every source shares it — the query is scoped. */
  readonly machine = input('');
  /**
   * The protocol a citation marker was last clicked for.
   *
   * An input rather than a method the parent calls, so the parent needs no handle on this
   * component: it sets a signal, and the effect below opens and reveals the card. A wrapper object
   * rather than a bare id because clicking the same marker twice has to be two events — see
   * {@link SourceFocus}.
   */
  readonly focused = input<SourceFocus | null>(null);

  /** A card's title was activated — the parent owns the viewer dialog. */
  readonly opened = output<Citation>();

  private readonly expanded = signal<ReadonlySet<string>>(new Set());
  /** Which card the last marker click pointed at, so it can be picked out of a list of five. */
  protected readonly highlighted = signal<string | null>(null);

  protected readonly collapsible = computed(() => this.citations().length > COLLAPSE_ABOVE);

  /** Whether every card is open, so the control can say which way it goes. */
  protected readonly allExpanded = computed(() => {
    const citations = this.citations();
    return citations.length > 0 && citations.every((c) => this.expanded().has(c.protocolId));
  });

  constructor() {
    effect(() => {
      const protocolId = this.focused()?.protocolId;
      if (!protocolId) {
        return;
      }
      // A MARKER MUST NEVER DEAD-END. That is the bug class this project already caught once (#26:
      // a citation link that answered 401 and did nothing visible), and a collapsed card is a fresh
      // way to reproduce it — a click that scrolled to a title with no detail reads as "nothing
      // happened". So the card opens first, then the page moves to it.
      this.expanded.update((current) => new Set(current).add(protocolId));
      this.highlighted.set(protocolId);
      const card = this.document.getElementById(`source-${protocolId}`);
      // Guarded because jsdom, where the tests run, does not implement scrollIntoView. What is worth
      // asserting is that the right card ends up open; a missing DOM API must not take the handler
      // down with it.
      card?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    });
  }

  protected cardId(citation: Citation): string {
    return sourceCardId(citation);
  }

  protected isExpanded(citation: Citation): boolean {
    return !this.collapsible() || this.expanded().has(citation.protocolId);
  }

  protected toggle(citation: Citation): void {
    this.expanded.update((current) => {
      const next = new Set(current);
      if (!next.delete(citation.protocolId)) {
        next.add(citation.protocolId);
      }
      return next;
    });
  }

  protected toggleAll(): void {
    this.expanded.set(
      this.allExpanded() ? new Set() : new Set(this.citations().map((c) => c.protocolId)),
    );
  }

  /** Percent, rounded — a reader compares 69 % with 56 %, not 0.6896 with 0.5566. */
  protected percent(citation: Citation): number {
    return Math.round(citation.similarity * 100);
  }

  /**
   * The citation's approval, in the shape the shared badge reads.
   *
   * A citation carries a boolean rather than an actor and a time, and that is the right wire shape:
   * a technician reading an answer needs to know whether a source was reviewed, not by whom — and
   * the approver's username on every source card would put a colleague's name under a claim they
   * did not make. Widened here rather than in the badge so the badge stays one component with one
   * input, used identically on all three surfaces.
   */
  protected approvalOf(citation: Citation): Approval {
    return {
      state: citation.approved ? 'APPROVED' : 'UNAPPROVED',
      approvedBy: null,
      approvedAt: null,
    };
  }
}
