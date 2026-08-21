import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import {
  ArchivedProtocol,
  DeletedProtocolPage,
  ModeratedProtocol,
  ProtocolPage,
} from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Moderation } from './moderation';

/**
 * Scaffolding shared by the moderation sibling specs.
 *
 * <p>It exists because moderation.spec.ts is 1192 lines — far past the 450-line alarm the standards
 * set for a component spec — so the coverage work went into sibling files rather than into that
 * one. Two siblings needing the same TestBed, the same builders and the same "answer the two calls
 * the constructor makes" dance is what a fixtures module is for.
 *
 * <p><b>There are no assertions in here, deliberately.</b> A fixtures module that decided what a
 * case asserts would make every consuming test a test of this file. It provides stubs, builders and
 * arrangement; what any of it MEANS is the spec's business.
 *
 * <p>Consumers: moderation.interaction.spec.ts, moderation.dialogs.spec.ts,
 * moderation.failures.spec.ts.
 */

/** The roles the signed-in reader holds. Flipped per test, because the buttons depend on them. */
export type Roles = WritableSignal<string[]>;

export interface ModerationHarness {
  readonly http: HttpTestingController;
  readonly roles: Roles;
}

/**
 * Configures the TestBed and returns the handles a spec needs.
 *
 * <p>Call from `beforeEach`. The language is pinned to German because the interface under test is
 * German and an unpinned one would read whatever localStorage held from the previous file.
 */
export async function setUpModeration(roles: string[] = ['admin']): Promise<ModerationHarness> {
  // Entry clear: this file defends itself rather than trusting whoever ran before it. I18nService
  // reads a stored language on construction, and under shared workers that value can outlive a file.
  localStorage.clear();
  // A test that fails mid-way leaves the TestBed instantiated, and every later configure in this
  // file would then throw for a reason unrelated to what it tests. One real failure should stay one.
  TestBed.resetTestingModule();

  const roleSignal = signal<string[]>(roles);

  await TestBed.configureTestingModule({
    imports: [Moderation],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      // A stub rather than a fake token: what these specs are about is which controls a role sees,
      // and base64 in the way of that would not make the answer truer.
      { provide: AuthService, useValue: { realmRoles: roleSignal } },
    ],
  }).compileComponents();

  const http = TestBed.inject(HttpTestingController);
  TestBed.inject(I18nService).use('de');

  return { http, roles: roleSignal };
}

/** Exit clear: the only cleanup the next file inherits. Call from `afterEach`. */
export function tearDownModeration(http: HttpTestingController): void {
  http.verify();
  localStorage.clear();
}

export function protocol(overrides: Partial<ModeratedProtocol> = {}): ModeratedProtocol {
  return {
    id: 'p-1',
    machineNo: 'PR-03',
    title: 'E-47 Druckabfall',
    protocolType: 'STOERUNG',
    errorCode: 'E-47',
    uploadedBy: 'schichtleiter',
    uploadedAt: '2026-08-08T10:15:00Z',
    status: 'INDEXED',
    chunkCount: 2,
    // Approved by default, like 150 of the 165 seeded protocols: an UNAPPROVED default would make
    // every consuming test a test of the approval queue.
    approval: { state: 'APPROVED', approvedBy: 'admin', approvedAt: '2026-08-11T09:00:00Z' },
    ...overrides,
  };
}

export function archived(overrides: Partial<ArchivedProtocol> = {}): ArchivedProtocol {
  return {
    id: 'a-1',
    machineNo: 'PR-03',
    title: 'E-47 Fehlmessung',
    protocolType: 'STOERUNG',
    errorCode: 'E-47',
    uploadedBy: 'schichtleiter',
    uploadedAt: '2026-08-08T10:15:00Z',
    deletedAt: '2026-08-12T08:00:00Z',
    deletedBy: 'admin',
    deleteComment: 'Doppelt erfasst.',
    ...overrides,
  };
}

export function page(items: ModeratedProtocol[], total = items.length, index = 0): ProtocolPage {
  return { items, page: index, size: 5, total };
}

export function archivePage(
  items: ArchivedProtocol[],
  total = items.length,
  index = 0,
  cap = 50,
): DeletedProtocolPage {
  return { items, page: index, size: 5, total, cap };
}

/**
 * Creates the component and answers the two calls its constructor makes.
 *
 * <p>Both are answered here because neither is what any consuming spec is about: a test that had to
 * flush the machine list before it could click a tab would be describing the constructor.
 */
export async function renderModeration(
  http: HttpTestingController,
  first: ProtocolPage = page([protocol()]),
): Promise<ComponentFixture<Moderation>> {
  const fixture = TestBed.createComponent(Moderation);
  http.expectOne((request) => request.url === '/api/moderation/protocols').flush(first);
  http.expectOne('/api/machines').flush([
    { id: 'm-1', machineNo: 'PR-03', name: 'Presse 3', type: 'Presse', location: 'Halle 1' },
    { id: 'm-2', machineNo: 'AB-02', name: 'Abfüller 2', type: 'Abfüller', location: 'Halle 2' },
  ]);
  await fixture.whenStable();
  return fixture;
}

/** The element carrying a test id, or null when the view does not render it. */
export function find(fixture: ComponentFixture<unknown>, testId: string): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);
}

/** The element carrying a test id. Throws when it is absent, so a typo fails as a typo. */
export function get(fixture: ComponentFixture<unknown>, testId: string): HTMLElement {
  const element = find(fixture, testId);
  if (!element) {
    throw new Error(`No element with data-testid="${testId}"`);
  }
  return element;
}

/** Clicks by test id and lets the resulting change detection settle. */
export async function click(fixture: ComponentFixture<unknown>, testId: string): Promise<void> {
  get(fixture, testId).click();
  await fixture.whenStable();
}

/**
 * Fills a field the way a user does — through the event Angular's forms binding listens for — so
 * the template's own listener runs rather than a signal being set behind it.
 */
export async function type(
  fixture: ComponentFixture<unknown>,
  testId: string,
  value: string,
): Promise<void> {
  const field = get(fixture, testId) as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
  field.value = value;
  field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'));
  await fixture.whenStable();
}

/**
 * Opens the correction dialog on the first row and answers the document fetch it makes.
 *
 * <p>AS THE SCHICHTLEITER, because correcting has been theirs since 2026-08-13 and the
 * administrator has no Bearbeiten button at all. The role is set here so a consuming test can go on
 * saying what the dialog does rather than repeating who may open it.
 */
export async function openEdit(
  fixture: ComponentFixture<Moderation>,
  http: HttpTestingController,
  roles: Roles,
  text = 'Anzugsmoment 90 Nm',
): Promise<void> {
  roles.set(['schichtleiter']);
  await fixture.whenStable();
  await click(fixture, 'row-edit');
  http
    .expectOne('/api/moderation/protocols/p-1/document')
    .flush(new Blob([text], { type: 'text/plain' }));
  await fixture.whenStable();
  await fixture.whenStable();
}

/**
 * Opens the read-only viewer on a row and answers BOTH calls it makes.
 *
 * <p>The history call is easy to forget and impossible to ignore: an unanswered request fails
 * `verify()` in the teardown, which is the mechanism that makes "nothing else was sent" an assertion
 * rather than a hope.
 */
export async function openViewer(
  fixture: ComponentFixture<Moderation>,
  http: HttpTestingController,
  options: { testId?: string; id?: string; source?: 'moderation' | 'archive' } = {},
): Promise<void> {
  const { testId = 'row-open', id = 'p-1', source = 'moderation' } = options;
  await click(fixture, testId);
  const path =
    source === 'archive'
      ? `/api/moderation/protocols/deleted/${id}/document`
      : `/api/moderation/protocols/${id}/document`;
  http.expectOne(path).flush(new Blob(['Symptom:\nKein Druck.\n'], { type: 'text/plain' }));
  // BOTH sources read a history, including the archive: a removed protocol still has a ledger, and
  // reviewing a removal is exactly when a reader wants to see what happened to it before.
  http
    .expectOne(`/api/moderation/protocols/${id}/history`)
    .flush({ events: [], total: 0, limit: 3 });
  await fixture.whenStable();
  await fixture.whenStable();
}

/** Switches to the archive tab and answers the page it loads on arrival. */
export async function openArchive(
  fixture: ComponentFixture<Moderation>,
  http: HttpTestingController,
  first: DeletedProtocolPage = archivePage([archived()]),
): Promise<void> {
  await click(fixture, 'tab-archive');
  http.expectOne((request) => request.url === '/api/moderation/protocols/deleted').flush(first);
  await fixture.whenStable();
}

/**
 * Answers the duplicate check that every approval makes first, with nothing similar.
 *
 * <p>The empty answer is the ordinary case — no dialog, one click — so a spec that is not about
 * duplicate detection only needs it flushed and out of the way.
 */
export function noSimilar(http: HttpTestingController, id = 'p-1'): void {
  http.expectOne(`/api/moderation/protocols/${id}/similar`).flush({
    comparable: true,
    candidates: [],
    total: 0,
    allIds: [],
    threshold: 0.92,
  });
}
