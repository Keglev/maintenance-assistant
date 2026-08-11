import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { HealthStatus } from '../../core/api/api.types';
import { HealthDot } from './health-dot';
import { I18nService } from '../../core/i18n/i18n.service';

/**
 * The footer's server-status dot.
 *
 * Two things are worth asserting beyond "it turns green": that a hanging backend reads as DOWN
 * rather than as forever-pending, and that a poll returning the same state does not re-announce
 * itself to a screen reader every minute — which is how a status line becomes something a user
 * turns off.
 */
describe('HealthDot', () => {
  let httpMock: HttpTestingController;

  const POLL_MS = 60_000;
  const TIMEOUT_MS = 5_000;

  beforeEach(async () => {
    // Fake timers before the component exists: it polls from its constructor, and the interval it
    // registers has to be the fake one.
    vi.useFakeTimers();
    await TestBed.configureTestingModule({
      imports: [HealthDot],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(I18nService).use('de');
  });

  afterEach(() => {
    httpMock.verify();
    vi.useRealTimers();
  });

  function element(fixture: ComponentFixture<HealthDot>): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="health-dot"]',
    ) as HTMLElement;
  }

  /**
   * Creates the dot and answers the request it makes immediately.
   *
   * `detectChanges()` rather than `whenStable()`: this application is zoneless, so `whenStable`
   * waits on the scheduler — which runs on the very timers this suite has replaced with fake ones,
   * and therefore never settles. Every one of these assertions is about the DOM after a known
   * synchronous event, so rendering it explicitly is both correct and less machinery.
   */
  async function render(body: HealthStatus = { status: 'UP', version: '1.1.0' }) {
    const fixture = TestBed.createComponent(HealthDot);
    httpMock.expectOne('/api/health').flush(body);
    fixture.detectChanges();
    return fixture;
  }

  it('asks once as soon as it is on screen', async () => {
    const fixture = TestBed.createComponent(HealthDot);

    // Rendered only while signed in, so "on screen" is "just logged in" — no separate subscription
    // to the auth state, and nothing polling behind a logged-out landing page.
    const request = httpMock.expectOne('/api/health');
    expect(request.request.method).toBe('GET');
    request.flush({ status: 'UP', version: '1.1.0' });
    fixture.detectChanges();
  });

  it('shows nothing but neutral until the first answer arrives', async () => {
    const fixture = TestBed.createComponent(HealthDot);
    fixture.detectChanges();

    // A guess in either direction before the first answer would be a lie either way.
    expect(element(fixture).getAttribute('data-state')).toBe('unknown');
    expect(element(fixture).textContent).toContain('wird geprüft');

    httpMock.expectOne('/api/health').flush({ status: 'UP', version: '1.1.0' });
    fixture.detectChanges();
  });

  it('is up on a 2xx that says UP', async () => {
    const fixture = await render();

    expect(element(fixture).getAttribute('data-state')).toBe('up');
    expect(element(fixture).textContent).toContain('Server: online');
  });

  it('is down on a 2xx that says anything else', async () => {
    const fixture = await render({ status: 'DOWN', version: '1.1.0' });

    // The body is read as well as the status code: a 200 carrying something other than UP is the
    // backend telling us something, and reading only the code would throw that away.
    expect(element(fixture).getAttribute('data-state')).toBe('down');
    expect(element(fixture).textContent).toContain('Server: offline');
  });

  it('is down on a non-2xx', async () => {
    const fixture = TestBed.createComponent(HealthDot);
    httpMock
      .expectOne('/api/health')
      .flush({}, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(element(fixture).getAttribute('data-state')).toBe('down');
  });

  it('is down on a network error', async () => {
    const fixture = TestBed.createComponent(HealthDot);
    httpMock.expectOne('/api/health').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(element(fixture).getAttribute('data-state')).toBe('down');
  });

  it('is down when the request hangs, rather than pending forever', async () => {
    const fixture = TestBed.createComponent(HealthDot);
    // Opened and never answered — a backend that accepts the connection and goes quiet. Without the
    // client-side timeout the dot would sit on "checking" for as long as the tab is open, which is
    // the one state that tells the reader nothing.
    const request = httpMock.expectOne('/api/health');

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    fixture.detectChanges();

    expect(element(fixture).getAttribute('data-state')).toBe('down');
    // The request is cancelled by the timeout, so nothing is left in flight for verify() to find.
    expect(request.cancelled).toBe(true);
  });

  it('asks again every sixty seconds', async () => {
    const fixture = await render();

    await vi.advanceTimersByTimeAsync(POLL_MS);
    httpMock.expectOne('/api/health').flush({ status: 'DOWN', version: '1.1.0' });
    fixture.detectChanges();
    expect(element(fixture).getAttribute('data-state')).toBe('down');

    await vi.advanceTimersByTimeAsync(POLL_MS);
    httpMock.expectOne('/api/health').flush({ status: 'UP', version: '1.1.0' });
    fixture.detectChanges();
    expect(element(fixture).getAttribute('data-state')).toBe('up');
  });

  it('does not ask before the interval is due', async () => {
    const fixture = await render();

    await vi.advanceTimersByTimeAsync(POLL_MS - 1_000);
    fixture.detectChanges();

    // httpMock.verify() in afterEach is what proves no second request went out.
    expect(element(fixture).getAttribute('data-state')).toBe('up');
  });

  it('stops polling when it leaves the screen — which is what logging out does', async () => {
    const fixture = await render();

    // The footer renders this only while authenticated, so destroying it IS the logout path.
    fixture.destroy();
    await vi.advanceTimersByTimeAsync(POLL_MS * 3);

    // Nothing further was requested; verify() in afterEach fails the test if anything was.
    expect(true).toBe(true);
  });

  it('re-announces only when the state actually changed', async () => {
    const fixture = await render();
    const region = element(fixture);

    // A polite live region speaks when its CONTENT changes. Two things have to hold for that to
    // mean "on change only": the text must be identical after an unchanged poll, and the element
    // must be the same node — rebuilding it per state would re-announce every sixty seconds.
    expect(region.getAttribute('role')).toBe('status');
    expect(region.getAttribute('aria-live')).toBe('polite');
    const before = region.textContent;

    await vi.advanceTimersByTimeAsync(POLL_MS);
    httpMock.expectOne('/api/health').flush({ status: 'UP', version: '1.1.0' });
    fixture.detectChanges();

    expect(element(fixture)).toBe(region);
    expect(region.textContent).toBe(before);

    await vi.advanceTimersByTimeAsync(POLL_MS);
    httpMock.expectOne('/api/health').flush({ status: 'DOWN', version: '1.1.0' });
    fixture.detectChanges();

    expect(element(fixture)).toBe(region);
    expect(region.textContent).not.toBe(before);
  });

  it('never carries the state in colour alone', async () => {
    const fixture = await render();

    // The same rule the answer modes follow (NFR-2): a difference that exists only in colour is a
    // difference some readers do not get. The dot itself is hidden from assistive technology,
    // because the label beside it already says the same thing.
    expect(element(fixture).querySelector('.health-dot')?.getAttribute('aria-hidden')).toBe('true');
    expect(element(fixture).querySelector('.health-label')?.textContent?.trim()).toBe(
      'Server: online',
    );
  });

  it('says it in the interface language', async () => {
    const fixture = await render();

    TestBed.inject(I18nService).use('en');
    fixture.detectChanges();

    expect(element(fixture).textContent).toContain('Server: online');

    await vi.advanceTimersByTimeAsync(POLL_MS);
    httpMock.expectOne('/api/health').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(element(fixture).textContent).toContain('Server: offline');
  });
});
