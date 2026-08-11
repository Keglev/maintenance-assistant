import { Component, inject, signal } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * "Wie suche ich?" — the panel in the empty right half of the search view.
 *
 * <p><b>It is the designed answer to the lazy-query problem, not decoration.</b> Retrieval here is
 * semantic only, by architecture (ADR-004): there is no keyword index to fall back on, so a
 * one-word question is not a narrower search, it is a worse one — the embedding of "frei" is close
 * to nothing in particular. A user who does not know that types one word, gets Mode B, and
 * concludes the assistant is broken. Explaining the rule beside the field costs a paragraph; the
 * alternative is building a keyword search that was deliberately not built.
 *
 * <p>Its own component because the styling is a third of the search view's stylesheet and none of
 * it is about answers. That also keeps `search.css` inside the per-component budget, which the
 * combined file had just exceeded.
 *
 * <p><b>Subordinate by construction</b>: no card, no shadow, helper-grey text behind a left rule.
 * Once an answer arrives it must not compete with it — the answer is what the reader came for.
 */
@Component({
  selector: 'app-search-help',
  templateUrl: './search-help.html',
  styleUrl: './search-help.css',
})
export class SearchHelp {
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  /**
   * Whether the panel is open.
   *
   * Only narrow viewports read this: on a wide screen the panel sits beside the form and the
   * stylesheet keeps it open regardless, because collapsing something already next to the field it
   * explains costs a click and buys nothing. Closed by default, so a phone shows the form first.
   */
  protected readonly open = signal(false);
}
