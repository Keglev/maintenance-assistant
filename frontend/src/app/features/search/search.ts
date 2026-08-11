import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Citation, Machine, QueryAnswer } from '../../core/api/api.types';
import { ApiFailure, MaintenanceApiService, classify } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { ProtocolDialog } from '../../shared/protocol/protocol-dialog';
import { SearchAnswer } from './search-answer';
import { SearchHelp } from './search-help';

/**
 * The search view (US-1/US-2): the question form, and the two things that answer it.
 *
 * <p>Two columns on a wide screen — the form and its answer on the left, the help panel on the
 * right — and one column below 64rem. The answer belongs directly under the form that produced it:
 * it rendered after the help panel before, which meant scrolling past an explanation to reach the
 * thing being explained.
 *
 * <p>The answer block itself is {@link SearchAnswer}. The two modes are visually distinct there,
 * which is not styling but NFR-2: a Mode B answer that looked like a Mode A one would be the
 * failure this application exists to prevent.
 */
@Component({
  selector: 'app-search',
  imports: [FormsModule, ProtocolDialog, SearchAnswer, SearchHelp],
  templateUrl: './search.html',
  styleUrl: './search.css',
})
export class Search {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  protected readonly machines = signal<Machine[]>([]);
  protected readonly machinesFailed = signal(false);
  protected readonly machineId = signal('');
  protected readonly question = signal('');

  protected readonly answer = signal<QueryAnswer | null>(null);
  protected readonly asking = signal(false);
  protected readonly failure = signal<ApiFailure | null>(null);
  protected readonly validation = signal<'question' | 'machine' | null>(null);

  /** The citation whose protocol the viewer is showing, or null when the viewer is closed. */
  protected readonly viewing = signal<Citation | null>(null);

  /** What to call the machine in the viewer's head: the picker's own label, not an id. */
  protected readonly machineLabel = computed(() => {
    const machine = this.machines().find((candidate) => candidate.id === this.machineId());
    return machine ? `${machine.machineNo} · ${machine.name}` : '';
  });

  constructor() {
    this.api.machines().subscribe({
      next: (machines) => this.machines.set(machines),
      // The view still renders and says the list is missing, rather than showing an empty picker
      // that looks like a plant with no machines in it.
      error: () => this.machinesFailed.set(true),
    });
  }

  protected ask(): void {
    if (this.asking()) {
      return;
    }
    const question = this.question().trim();
    if (!this.machineId()) {
      this.validation.set('machine');
      return;
    }
    if (!question) {
      this.validation.set('question');
      return;
    }

    this.validation.set(null);
    this.failure.set(null);
    // The previous answer is cleared before the new one is requested. Leaving it on screen for the
    // 5-20 seconds this takes would show one machine's answer under another machine's name.
    this.answer.set(null);
    this.asking.set(true);

    this.api.ask(question, this.machineId()).subscribe({
      next: (answer) => {
        this.answer.set(answer);
        this.asking.set(false);
      },
      error: (error: unknown) => {
        this.failure.set(classify(error));
        this.asking.set(false);
      },
    });
  }

  /**
   * Opens the protocol behind a citation, in a dialog, inside the application.
   *
   * **Why this is a handler and not an `href`.** It was an `href`, and it did not work: a
   * browser-followed anchor is a fresh navigation that never passes through Angular's interceptor
   * chain, so it carries no `Authorization` header, and the backend is a stateless JWT resource
   * server with no cookie fallback — every click answered 401.
   *
   * The first fix (#26) fetched the document through HttpClient and opened it in a new tab as an
   * object URL. That was right about the token and wrong about the destination: it dropped the
   * reader into the browser's raw text viewer, out of the application, which on a shop-floor tablet
   * is somewhere hard to come back from. The viewer dialog keeps the document here, and the whole
   * question of what a navigation carries stops applying.
   *
   * All this method does now is name the citation — the dialog owns the fetch, the parsing and the
   * download.
   */
  protected openSource(citation: Citation, event?: Event): void {
    event?.preventDefault();
    this.viewing.set(citation);
  }
}
