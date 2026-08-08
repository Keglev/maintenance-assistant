import { DOCUMENT } from '@angular/common';
import { Component, ElementRef, effect, inject, input, output, viewChild } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * The help dialog: what the two answer modes mean, what each role may do, and three questions that
 * actually retrieve something.
 *
 * **Why not `<dialog>`.** The native element gives focus trapping and Esc for free, and it is the
 * right choice in a browser — but the test runner is vitest in jsdom, which does not implement
 * `showModal()`, so a native dialog would be untestable in the only place this project runs tests.
 * The behaviour is therefore implemented here: Esc closes, Tab cycles inside, the close button takes
 * focus on open and the opener gets it back on close. That is the part users notice; the element is
 * an implementation detail.
 */
@Component({
  selector: 'app-help-dialog',
  templateUrl: './help-dialog.html',
  styleUrl: './help-dialog.css',
})
export class HelpDialog {
  private readonly i18n = inject(I18nService);
  private readonly document = inject(DOCUMENT);

  protected readonly t = this.i18n.t;

  readonly open = input(false);
  readonly closed = output<void>();

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

  /** Whoever opened the dialog, so focus can be handed back where it came from. */
  private opener: HTMLElement | null = null;

  constructor() {
    effect(() => {
      if (this.open()) {
        this.opener = this.document.activeElement as HTMLElement | null;
        // The panel is rendered in the same change-detection pass, so the focus move waits a tick.
        setTimeout(() => this.focusables()[0]?.focus());
      } else {
        this.opener?.focus();
        this.opener = null;
      }
    });
  }

  protected close(): void {
    this.closed.emit();
  }

  /** Esc closes from anywhere inside the dialog — the shortcut every modal is expected to have. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close();
      return;
    }
    if (event.key === 'Tab') {
      this.trapTab(event);
    }
  }

  /**
   * Keeps Tab inside the dialog.
   *
   * Without this, tabbing walks out of a modal and onto the page behind it, which for a screen
   * reader user means the dialog silently stops being where they are.
   */
  private trapTab(event: KeyboardEvent): void {
    const focusable = this.focusables();
    if (focusable.length === 0) {
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = this.document.activeElement;

    if (event.shiftKey && (active === first || !focusable.includes(active as HTMLElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusables(): HTMLElement[] {
    const host = this.panel()?.nativeElement;
    return host
      ? [
          ...host.querySelectorAll<HTMLElement>(
            'button, [href], input, select, textarea, [tabindex]',
          ),
        ]
      : [];
  }
}
