import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18nService } from '../../core/i18n/i18n.service';
import { HelpDialog } from './help-dialog';

/**
 * The dialog is where a visitor learns what green and amber mean, so the assertions are that it
 * says both things, that it can be dismissed the three ways a modal is expected to close, and that
 * the keyboard does not fall out of it.
 */
@Component({
  imports: [HelpDialog],
  template: `<button type="button" id="opener" (click)="open.set(true)">open</button>
    <app-help-dialog [open]="open()" (closed)="open.set(false)" />`,
})
class HostComponent {
  readonly open = signal(false);
}

describe('HelpDialog', () => {
  async function render(language: 'de' | 'en' = 'de') {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    TestBed.inject(I18nService).use(language);

    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    return fixture;
  }

  /** Opens the dialog through the button, which is how the shell opens it too. */
  async function open(fixture: Awaited<ReturnType<typeof render>>) {
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#opener') as HTMLButtonElement).click();
    await fixture.whenStable();
    return element;
  }

  beforeEach(() => localStorage.clear());

  it('renders nothing until it is opened', async () => {
    const fixture = await render();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="help-dialog"]'),
    ).toBeNull();
  });

  it('explains both answer modes', async () => {
    const fixture = await render();
    const element = await open(fixture);

    // Both, and in the same dialog: the point of the help is the contrast between them.
    expect(element.querySelector('[data-testid="help-mode-a"]')?.textContent).toContain(
      'Belegte Antwort',
    );
    expect(element.querySelector('[data-testid="help-mode-b"]')?.textContent).toContain(
      'Allgemeiner Vorschlag',
    );
  });

  it('offers one example question per demo seed', async () => {
    const fixture = await render();
    const element = await open(fixture);

    const examples = element.querySelectorAll('[data-testid="help-example"]');
    expect(examples.length).toBe(3);
    // The Mode-B gap is the one a visitor would never think to ask for.
    expect(element.textContent).toContain('AB-02');
  });

  it('marks itself as a modal dialog with an accessible name', async () => {
    const fixture = await render();
    const element = await open(fixture);
    const dialog = element.querySelector('[data-testid="help-dialog"]') as HTMLElement;

    expect(dialog.getAttribute('role')).toBe('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(element.querySelector('#' + dialog.getAttribute('aria-labelledby'))).not.toBeNull();
  });

  it('closes on the close button, on the backdrop and on Escape', async () => {
    const fixture = await render();

    const element = await open(fixture);
    (element.querySelector('[data-testid="help-close"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="help-dialog"]')).toBeNull();

    await open(fixture);
    (element.querySelector('[data-testid="help-backdrop"]') as HTMLElement).click();
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="help-dialog"]')).toBeNull();

    await open(fixture);
    element
      .querySelector('[data-testid="help-backdrop"]')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(element.querySelector('[data-testid="help-dialog"]')).toBeNull();
  });

  it('does not close when the click lands inside the panel', async () => {
    const fixture = await render();
    const element = await open(fixture);

    (element.querySelector('[data-testid="help-dialog"]') as HTMLElement).click();
    await fixture.whenStable();

    // A click on the text of a dialog dismissing it is the classic way to lose what you were reading.
    expect(element.querySelector('[data-testid="help-dialog"]')).not.toBeNull();
  });

  it('keeps Tab inside the dialog', async () => {
    const fixture = await render();
    const element = await open(fixture);
    const close = element.querySelector('[data-testid="help-close"]') as HTMLButtonElement;

    // The close button is the only focusable element in the panel, so both directions wrap onto it
    // — which is exactly what a trap has to do rather than letting focus escape to the page behind.
    close.focus();
    element
      .querySelector('[data-testid="help-backdrop"]')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    await fixture.whenStable();

    expect(document.activeElement).toBe(close);
  });

  it('translates its content with the interface language', async () => {
    const fixture = await render('en');
    const element = await open(fixture);

    expect(element.querySelector('[data-testid="help-dialog"]')?.textContent).toContain(
      'sourced answer',
    );
  });
});
