import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Approval } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { ApprovalStateBadge } from './approval-state';

/**
 * The marker that keeps decision 1 of 2026-08-11 honest.
 *
 * That decision lets an unapproved protocol stay searchable and citable, and accepts that it is
 * less reliable to troubleshoot from. The whole of what is given in return is this badge: an
 * unmarked unreviewed source cited in a Mode A answer would make an unreviewed claim look exactly
 * as checked as a reviewed one, which is NFR-2's citation discipline working against itself.
 *
 * So what these tests protect is not styling. It is that the words appear, that the icon appears
 * beside them, and that neither state is told in colour alone.
 */
@Component({
  imports: [ApprovalStateBadge],
  template: `<app-approval-state [approval]="approval()" />`,
})
class Host {
  readonly approval = signal<Approval>({ state: 'UNAPPROVED', approvedBy: null, approvedAt: null });
}

describe('ApprovalStateBadge', () => {
  const APPROVED: Approval = {
    state: 'APPROVED',
    approvedBy: 'admin',
    approvedAt: '2026-08-11T09:30:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    TestBed.inject(I18nService).use('de');
  });

  async function render(approval?: Approval) {
    const fixture = TestBed.createComponent(Host);
    if (approval) {
      fixture.componentInstance.approval.set(approval);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('names an unapproved protocol in words, not only in colour', async () => {
    const element = await render();
    const badge = element.querySelector('[data-testid="approval-state"]');

    expect(badge?.textContent).toContain('Nicht freigegeben');
    // The state is also machine-readable, which is what the e2e suite and the CSS both key on —
    // asserting the class alone would let the word disappear without a test noticing.
    expect(badge?.getAttribute('data-approval')).toBe('UNAPPROVED');
  });

  it('carries an icon beside the word in both states', async () => {
    // The project's accessibility line: a difference that exists only in colour is a difference some
    // readers do not get. Two signals, always — and this is the assertion that fails if a redesign
    // ever reduces either state to a coloured dot.
    for (const approval of [undefined, APPROVED]) {
      const element = await render(approval);
      expect(element.querySelectorAll('[data-testid="approval-state"] svg').length).toBe(1);
      expect(
        element
          .querySelector('[data-testid="approval-state"] .approval-label')
          ?.textContent?.trim(),
      ).not.toBe('');
    }
  });

  it('says the STATE and never the actor, in either state', async () => {
    // THE 2026-08-14 RULE, and the regression this test exists to catch. This badge renders in a
    // table cell, a source card and a dialog head; when it also carried the approver and the date,
    // the widest form of it — "Freigegeben system:corpus-seed · 12.08.2026" — overflowed the
    // Verwaltung column in production and ran under the action buttons.
    //
    // WHO and WHEN are provenance and belong in the record, not in every list that mentions it:
    // they are in the protocol viewer's history section now. If an actor ever reappears here, this
    // is what says the defect came back rather than a feature arriving.
    for (const approval of [undefined, APPROVED]) {
      const element = await render(approval);
      const badge = element.querySelector('[data-testid="approval-state"]');

      expect(badge?.querySelector('[data-testid="approval-actor"]')).toBeNull();
      expect(badge?.textContent).not.toContain('admin');
      expect(badge?.textContent).not.toContain('2026');
    }
  });

  it('is short enough that it cannot set a table column’s width', async () => {
    // The content half of the same fix, asserted rather than assumed: the badge is an icon and one
    // label, and the label is the longest string it can ever hold.
    const element = await render(APPROVED);
    const badge = element.querySelector('[data-testid="approval-state"]');

    expect(badge?.textContent?.trim()).toBe('Freigegeben');
  });
});
