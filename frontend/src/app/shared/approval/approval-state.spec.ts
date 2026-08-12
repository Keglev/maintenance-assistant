import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Approval } from '../../core/api/api.types';
import { I18nService } from '../../core/i18n/i18n.service';
import { UiLanguage } from '../../core/i18n/dictionary';
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
  template: `<app-approval-state
    [approval]="approval()"
    [language]="language()"
    [showActor]="showActor()"
  />`,
})
class Host {
  readonly approval = signal<Approval>({ state: 'UNAPPROVED', approvedBy: null, approvedAt: null });
  readonly language = signal<UiLanguage>('de');
  readonly showActor = signal(false);
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

  async function render(approval?: Approval, showActor = false) {
    const fixture = TestBed.createComponent(Host);
    if (approval) {
      fixture.componentInstance.approval.set(approval);
    }
    fixture.componentInstance.showActor.set(showActor);
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
        element.querySelector('[data-testid="approval-state"] .approval-label')?.textContent?.trim(),
      ).not.toBe('');
    }
  });

  it('names the approver and the day when the reader is auditing', async () => {
    const element = await render(APPROVED, true);
    const actor = element.querySelector('[data-testid="approval-actor"]')?.textContent ?? '';

    // An approval without an actor is not an audit record — the same rule the database constraint
    // states. The date follows the interface language, through the shared pipe.
    expect(actor).toContain('admin');
    expect(actor).toContain('11.08.2026');
  });

  it('says nothing about who, where the reader is a technician reading an answer', async () => {
    const element = await render(APPROVED);

    // A source card asks one question: was this reviewed. Putting a colleague's username under
    // every cited claim would answer a question nobody asked, about a person who made no claim.
    expect(element.querySelector('[data-testid="approval-actor"]')).toBeNull();
    expect(element.querySelector('[data-testid="approval-state"]')?.textContent).toContain(
      'Freigegeben',
    );
  });

  it('shows no actor line for an unapproved protocol, even when asked to', async () => {
    // There is nobody to name: the schema forbids an UNAPPROVED row from carrying an approver, so
    // a template that rendered the field regardless would print an empty separator.
    const element = await render(undefined, true);

    expect(element.querySelector('[data-testid="approval-actor"]')).toBeNull();
  });
});
