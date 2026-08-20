import { Component, inject, signal } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * "Wie suche ich?" — the panel in the empty right half of the search view.
 *
 * <p><b>It is the designed answer to the lazy-query problem, not decoration.</b> A user who does
 * not know how retrieval behaves types one word, gets Mode B, and concludes the assistant is
 * broken. Explaining the rule beside the field costs a paragraph.
 *
 * <p><b>THE PANEL TRACKS RETRIEVAL BEHAVIOUR, AND IT HAS BEEN WRONG ONCE.</b> Until 2026-08-20 it
 * taught that "ein Wort beschreibt keinen Fehler, und die Suche hat nichts, womit sie vergleichen
 * kann". That was true while retrieval was semantic only (ADR-004) and was FALSIFIED BY THE RUNNING
 * SYSTEM once ADR-009 added the lexical signal: a bare "E-47" now returns a grounded Mode A answer,
 * because {@code LexicalTerms} matches codes literally. Carlos's v1.3.0 drill typed it and watched
 * it work while this panel said it would not.
 *
 * <p>The text now states the rule the code actually implements: a term needs at least one LETTER
 * and at least one DIGIT to be matched literally. That is why "E-47" and "SV0410" work on their own
 * while "frei", "Druck" and "Band" still do not — and it is why a change to {@code LexicalTerms} is
 * also a change to this file. A help panel that contradicts observable behaviour is worse than none:
 * it teaches the reader to distrust the one thing on screen meant to orient them.
 *
 * <p><b>No validation gate</b>, decided 2026-08-20 and not to be re-proposed. Refusing to search
 * until a question "looks complete" would block "e-47" — a lazy query that WORKS — in order to
 * prevent "was bedeutet", which Mode B already handles gracefully. The cure costs more than the
 * disease.
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
