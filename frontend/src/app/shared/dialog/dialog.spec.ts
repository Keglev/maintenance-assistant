import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Dialog } from './dialog';

/**
 * The modal shell every dialog in this application is built from — and, until now, the only shared
 * component in the project with no spec at all.
 *
 * <p>THAT ABSENCE COST A REAL DEFECT. The class is written and commented as providing exactly three
 * things — "Esc closes, Tab cycles inside, the first control takes focus on open" — and in a real
 * browser it provided none of them for the protocol viewer, because the first control it tried to
 * focus was a DISABLED button and `focus()` on a disabled element does nothing and reports nothing.
 * Focus stayed outside the backdrop, so the keydown handler never saw a key. The e2e suite found it
 * (PR #50); this file is the half that can run in two seconds on every commit.
 *
 * <p><b>What jsdom can and cannot answer.</b> It can answer all of the below: it implements
 * `focus()`, `activeElement`, and refuses focus to disabled elements exactly as a browser does, so
 * the regression reproduces here. It cannot answer whether the FOCUS RING is visible, whether the
 * panel is actually on screen, or whether a real browser's own modal semantics interfere — which is
 * why `citation.e2e.ts` asserts the same behaviour again in Chromium. The two are not duplicates:
 * this one is fast and exhaustive, that one is true.
 */
@Component({
  imports: [Dialog],
  template: `<button type="button" id="opener" (click)="open.set(true)">open</button>
    <app-dialog [open]="open()" title="Test" testId="probe" (closed)="open.set(false)">
      @if (withDisabledFirst()) {
        <button type="button" id="disabled-first" dialogActions disabled>download</button>
      }
      @if (withBody()) {
        <input id="body-field" />
      }
    </app-dialog>`,
})
class HostComponent {
  readonly open = signal(false);
  /** Reproduces the viewer: the first projected control is disabled while its document loads. */
  readonly withDisabledFirst = signal(false);
  readonly withBody = signal(true);
}

describe('Dialog', () => {
  async function render() {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    return fixture;
  }

  type Fixture = Awaited<ReturnType<typeof render>>;

  const el = (fixture: Fixture) => fixture.nativeElement as HTMLElement;

  /**
   * Opens the dialog by clicking, which is how the application opens it — and it matters here,
   * because the click is what makes the button the `activeElement` that focus has to be taken from
   * and later handed back to.
   */
  async function open(fixture: Fixture) {
    const opener = el(fixture).querySelector('#opener') as HTMLButtonElement;
    opener.focus();
    opener.click();
    await fixture.whenStable();
    // The component moves focus in a macrotask, because the panel does not exist until the change
    // detection pass that this click triggered has finished rendering it.
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
    return el(fixture);
  }

  const panel = (fixture: Fixture) =>
    el(fixture).querySelector('[data-testid="probe-dialog"]') as HTMLElement;

  function press(fixture: Fixture, key: string, shiftKey = false) {
    const backdrop = el(fixture).querySelector('[data-testid="probe-backdrop"]') as HTMLElement;
    // Dispatched on the ACTIVE element so it bubbles the way a real key does. A test that dispatched
    // straight at the backdrop would pass even in the broken state, because it would skip the very
    // step that was broken: the key reaching the handler from wherever focus actually is.
    const target = (document.activeElement as HTMLElement | null) ?? backdrop;
    target.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true }));
  }

  it('moves focus into the panel when it opens', async () => {
    const fixture = await render();
    await open(fixture);

    expect(panel(fixture).contains(document.activeElement)).toBe(true);
  });

  it('skips a disabled first control instead of silently focusing nothing', async () => {
    // THE REGRESSION, in one test. Before the fix `focusables()[0]` was the disabled button, and
    // `.focus()` on it was a no-op — so focus stayed on the opener and every keyboard affordance
    // below stopped working.
    const fixture = await render();
    fixture.componentInstance.withDisabledFirst.set(true);
    await open(fixture);

    expect(document.activeElement?.id).not.toBe('disabled-first');
    expect(panel(fixture).contains(document.activeElement)).toBe(true);
  });

  it('focuses the panel itself when nothing inside it can take focus', async () => {
    const fixture = await render();
    fixture.componentInstance.withBody.set(false);
    fixture.componentInstance.withDisabledFirst.set(true);
    await open(fixture);

    // The close button is always there, so a dialog is never truly empty in this application — but
    // the fallback is what makes the guarantee unconditional rather than dependent on that.
    expect(panel(fixture).contains(document.activeElement)).toBe(true);
  });

  it('closes on Escape, pressed from wherever focus actually is', async () => {
    const fixture = await render();
    await open(fixture);

    press(fixture, 'Escape');
    await fixture.whenStable();

    expect(fixture.componentInstance.open()).toBe(false);
    expect(el(fixture).querySelector('[data-testid="probe-backdrop"]')).toBeNull();
  });

  it('hands focus back to whatever opened it', async () => {
    const fixture = await render();
    await open(fixture);

    press(fixture, 'Escape');
    await fixture.whenStable();

    // For a screen-reader user this is the difference between landing back where they were and
    // landing at the top of the document.
    expect(document.activeElement?.id).toBe('opener');
  });

  it('wraps Tab from the last control back to the first', async () => {
    const fixture = await render();
    await open(fixture);

    const focusable = [
      ...panel(fixture).querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled])',
      ),
    ];
    expect(focusable.length).toBeGreaterThan(1);

    focusable[focusable.length - 1].focus();
    press(fixture, 'Tab');

    expect(document.activeElement).toBe(focusable[0]);
  });

  it('wraps Shift+Tab from the first control back to the last', async () => {
    const fixture = await render();
    await open(fixture);

    const focusable = [
      ...panel(fixture).querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled])',
      ),
    ];
    focusable[0].focus();
    press(fixture, 'Tab', true);

    expect(document.activeElement).toBe(focusable[focusable.length - 1]);
  });

  it('keeps the panel out of the tab ring it contains', async () => {
    // The panel is `tabindex="-1"` so it can RECEIVE focus without being a STOP on the way round.
    // Without the exclusion in `focusables()` the generic `[tabindex]` selector would have made the
    // container the first thing Tab visits.
    const fixture = await render();
    await open(fixture);

    const first = panel(fixture).querySelector<HTMLElement>('button:not([disabled])')!;
    first.focus();
    press(fixture, 'Tab', true);

    expect(document.activeElement).not.toBe(panel(fixture));
  });
});
