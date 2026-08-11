import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { timeout } from 'rxjs';

import { MaintenanceApiService } from '../../core/api/maintenance-api.service';
import { I18nService } from '../../core/i18n/i18n.service';

/** How often the footer asks again, while it is on screen. */
const POLL_INTERVAL_MS = 60_000;

/**
 * How long a health request may hang before it counts as down.
 *
 * Without it a backend that accepts the connection and never answers leaves the dot on "checking"
 * forever, which is the one state that tells the reader nothing. Five seconds is far above the
 * endpoint's real cost — it builds no response, it returns a constant — so a slower answer than
 * this already means something is wrong.
 */
const REQUEST_TIMEOUT_MS = 5_000;

/** Neutral until the first answer arrives; a guess before then would be a lie either way. */
export type HealthState = 'unknown' | 'up' | 'down';

/**
 * The server-status dot in the footer.
 *
 * <p><b>A component rather than a service, and the reason is its lifetime.</b> The footer renders it
 * only while someone is signed in, so "start polling on login" and "stop polling on logout" are just
 * the component being created and destroyed — no second subscription to the auth signal, no token
 * check duplicated from {@link AuthService}, and nothing left running behind a logged-out landing
 * page. A service would have had to reproduce that lifecycle by hand and get it right twice.
 *
 * <p><b>It is a glance surface, not a monitoring system.</b> One failed request flips it to down.
 * There is deliberately no hysteresis, no retry with backoff and no reconnect banner: a footer dot
 * that stayed green through a real outage because it was waiting for a second opinion would be
 * worse than no dot, and one that grew a state machine would be a monitoring feature nobody asked
 * for. See the PR for the flapping question.
 *
 * <p><b>Never colour alone.</b> The dot is always beside its own text, because a status carried only
 * by hue is a status some readers do not get — the same rule the answer modes follow (NFR-2).
 */
@Component({
  selector: 'app-health-dot',
  templateUrl: './health-dot.html',
  styleUrl: './health-dot.css',
})
export class HealthDot {
  private readonly api = inject(MaintenanceApiService);
  private readonly i18n = inject(I18nService);

  protected readonly t = this.i18n.t;
  protected readonly state = signal<HealthState>('unknown');

  protected readonly label = computed(() => {
    const labels = this.t().footer;
    switch (this.state()) {
      case 'up':
        return labels.serverUp;
      case 'down':
        return labels.serverDown;
      default:
        return labels.serverUnknown;
    }
  });

  constructor() {
    this.check();
    const poll = setInterval(() => this.check(), POLL_INTERVAL_MS);
    // Cleared when the footer stops rendering this — which is what logging out does.
    inject(DestroyRef).onDestroy(() => clearInterval(poll));
  }

  private check(): void {
    this.api
      .health()
      .pipe(timeout(REQUEST_TIMEOUT_MS))
      .subscribe({
        // The body is checked as well as the status code: a 200 carrying anything other than UP is
        // the backend telling us something, and reading only the code would throw that away.
        next: (health) => this.state.set(health.status === 'UP' ? 'up' : 'down'),
        // One arm for every way this can fail — non-2xx, a network error, and the timeout above.
        // They differ to a developer and not to the person glancing at the footer, and inventing
        // three shades of "offline" would be three things to explain for one thing to do.
        error: () => this.state.set('down'),
      });
  }
}
