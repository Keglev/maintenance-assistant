import { Component, inject, input, output } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';
import { Dialog } from '../dialog/dialog';

/**
 * The help dialog: what the two answer modes mean, what each role may do, and three questions that
 * actually retrieve something.
 *
 * All the modal behaviour — focus trap, Esc, backdrop, the close button, full screen on a small
 * viewport — belongs to {@link Dialog} and is shared with the protocol viewer and the sign-out
 * confirmation. What is left here is the content, which is the only part specific to help.
 */
@Component({
  selector: 'app-help-dialog',
  imports: [Dialog],
  templateUrl: './help-dialog.html',
  styleUrl: './help-dialog.css',
})
export class HelpDialog {
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;

  readonly open = input(false);
  readonly closed = output<void>();
}
