import { DOCUMENT } from '@angular/common';
import { Component, ElementRef, effect, inject, input, output, viewChild } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/** Makes each instance's title element addressable by `aria-labelledby` without a collision. */
let sequence = 0;

/**
 * The modal shell every dialog in this application is built from: backdrop, head with a title and a
 * close button, scrolling body, focus trap, Esc.
 *
 * **Why not `<dialog>`.** The native element gives focus trapping and Esc for free, and it is the
 * right choice in a browser — but the test runner is vitest in jsdom, which does not implement
 * `showModal()`, so a native dialog would be untestable in the only place this project runs tests.
 * The behaviour is therefore implemented here: Esc closes, Tab cycles inside, the first control takes
 * focus on open and the opener gets it back on close. That is the part users notice; the element is
 * an implementation detail.
 *
 * It was the help dialog's own code until the shop floor needed two more dialogs. Extracting it is
 * what keeps "focus returns to the trigger" a property of *every* dialog rather than of the one that
 * happened to be written first.
 */
@Component({
  selector: 'app-dialog',
  templateUrl: './dialog.html',
  styleUrl: './dialog.css',
})
export class Dialog {
  private readonly i18n = inject(I18nService);
  private readonly document = inject(DOCUMENT);

  protected readonly t = this.i18n.t;

  readonly open = input(false);
  readonly title = input('');
  /**
   * What the dialog is called in the DOM, so a test can address one dialog rather than "the dialog".
   * The three parts get `<testId>-backdrop`, `<testId>-dialog` and `<testId>-close`.
   */
  readonly testId = input('dialog');
  readonly closed = output<void>();

  protected readonly titleId = `dialog-title-${++sequence}`;

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
