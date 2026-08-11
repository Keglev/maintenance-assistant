import { Component, computed, inject, input, output } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * The paging control the three tables share: a labelled arrow either side of "page X of Y".
 *
 * <p><b>The buttons carry their text, not only a chevron.</b> The primary device is a shop-floor
 * tablet operated with work gloves, and a bare arrow is both a smaller target and a guess — "Zurück"
 * next to a table could as easily mean leaving the view. Spelling out "Vorherige Seite" / "Nächste
 * Seite" costs width the page has and removes the guess.
 *
 * <p>Extracted rather than copied into each table because there are three of them now (the corpus,
 * the archive and "Meine Uploads") and the disabled-at-the-ends rule is the kind of thing that gets
 * fixed in one of three places.
 *
 * <p>Zero-based {@link page} in, one-based on screen: the API pages from 0 and people count from 1,
 * and the conversion belongs in one place rather than at every call site.
 */
@Component({
  selector: 'app-pager',
  templateUrl: './pager.html',
  styleUrl: './pager.css',
})
export class Pager {
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  /** Zero-based, as the API counts. */
  readonly page = input.required<number>();
  readonly pageCount = input.required<number>();
  /** Rows in the whole result, so the control can say what it is paging through. */
  readonly total = input.required<number>();
  /** What the pager is called for a screen reader, when a view has more than one. */
  readonly label = input('');
  /** Suffix for the test ids, so two pagers on one screen are addressable apart. */
  readonly testId = input('pager');

  readonly previous = output<void>();
  readonly next = output<void>();

  protected readonly hasPrevious = computed(() => this.page() > 0);
  protected readonly hasNext = computed(() => this.page() + 1 < this.pageCount());
}
