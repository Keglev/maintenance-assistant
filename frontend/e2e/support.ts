import { expect, Page, test as base } from '@playwright/test';

import { KEYCLOAK_URL } from './guard';

/**
 * Shared helpers. Deliberately small: a fixture layer that grows its own abstractions is a second
 * application to debug when a test goes red.
 */

/** The four published demo accounts of the seeded realm. Password is the same for all of them. */
export const DEMO_PASSWORD = 'demo1234';
export type DemoUser = 'operator' | 'techniker' | 'schichtleiter' | 'admin';

/** Anything this suite creates carries this prefix, so an artifact is identifiable at a glance. */
export const E2E_TITLE_PREFIX = 'E2E-THROWAWAY';

/**
 * The machine every writing test files against.
 *
 * Deliberately NOT PR-03: the E-47 demo case lives there, it is the thing the project is applied
 * with, and no test should be near it. Shared here because two files now write, and two copies of
 * "which machine is safe to write to" is one copy too many.
 */
export const E2E_MACHINE = 'VP-01';

/**
 * Signs in through the real Keycloak login page.
 *
 * <p>Through the REAL page on purpose. Seeding a token into sessionStorage would be faster and
 * would skip the one flow that has already broken once in production — the themed login page — so
 * the cost is paid on every test that needs a session.
 */
export async function signIn(page: Page, user: DemoUser): Promise<void> {
  await page.goto('/');
  await page.getByTestId('sign-in').click();

  // The browser is now on Keycloak's origin, not the app's.
  await page.waitForURL(`${KEYCLOAK_URL}/**`);
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(DEMO_PASSWORD);
  await page.locator('#kc-login').click();

  // Back on the app, signed in: the header shows who you are. Waiting on the identity rather than
  // on the URL, because the URL is correct for a moment before the token has been processed.
  await expect(page.getByTestId('header-username')).toContainText(user, { timeout: 30_000 });
}

/**
 * Chooses a machine in the SEARCH view's picker, by the label a person reads.
 *
 * <p>SHARED, because writing it twice is what went wrong. The search picker's option VALUES are
 * machine UUIDs while the upload form's are machine numbers — the two views genuinely differ — so
 * `selectOption('VP-01')` matches nothing here and throws. `citation.e2e.ts` had that bug and it was
 * fixed there; `reindex.e2e.ts` had the same bug and nobody found out, because it is skipped without
 * an LLM key and had therefore never run. Inside a `toPass` retry loop the failure is invisible: the
 * poll simply never gets past its first line until the timeout.
 *
 * <p>One helper, one place to be right.
 */
export async function selectSearchMachine(page: Page, machineNo: string): Promise<void> {
  const picker = page.getByTestId('machine-picker');
  // Asserted first, and with a short ceiling, so being on the wrong view fails in seconds with a
  // sentence rather than hanging until the test timeout — which is exactly what it did when this
  // helper was called from the upload view, where the picker is a different control entirely.
  await expect(picker, 'the search machine picker is not on this page — wrong view?').toBeVisible({
    timeout: 10_000,
  });
  const value = await picker
    .locator('option')
    .filter({ hasText: machineNo })
    .first()
    .getAttribute('value');
  if (!value) {
    throw new Error(
      `no option for machine ${machineNo} in the search picker — either the machine list did not ` +
        `load, or the corpus no longer contains that machine (fixture drift, not an app defect)`,
    );
  }
  await picker.selectOption(value);
}

/**
 * Archives every protocol this suite has ever left on a machine, through the UI, with a reason.
 *
 * <p>SHARED AND CALLED FROM EVERY TEST THAT WRITES, because the alternative was measured rather
 * than imagined: `moderation.e2e.ts` had this as a private `afterEach` and `reindex.e2e.ts` had
 * nothing at all, so each failed reindex run left an INDEXED protocol in the corpus. Seven of them
 * accumulated locally, and the eighth run then failed for a new reason — the answer already
 * contained the correction marker before the correction, because a previous run's corrected
 * protocol was still being retrieved. A test that poisons the next run is worse than no test.
 *
 * <p>Through the UI with a reason, never SQL: a cleanup that reached around the moderation ledger
 * would still pass if that ledger were broken, and the ledger is the thing these tests exist to
 * prove.
 *
 * <p>The caller must already be signed in as an admin and on the moderation view.
 */
export async function sweepThrowaways(page: Page, machineNo: string): Promise<number> {
  /**
   * Waits for the list to actually reflect the filter before anything is counted.
   *
   * <p>THIS LINE IS THE WHOLE FIX. `locator.count()` is one of the few Playwright APIs that does
   * NOT auto-wait: it reports what is on the page at the instant it is called. Called straight after
   * "Filter", that instant is before the request has come back, so it answered 0, the loop below
   * exited immediately, and the sweep reported success having archived nothing. It had never
   * removed a single protocol in either file that used it — a cleanup that silently does nothing is
   * indistinguishable from a cleanup that works, right up until a later run reads the leftovers as
   * data. Waiting for a settled list is the difference between counting rows and counting nothing.
   */
  const settled = () =>
    expect(
      page.getByTestId('moderation-table').or(page.getByTestId('moderation-empty')),
    ).toBeVisible();

  await page.getByTestId('tab-corpus').click();
  await page.getByTestId('filter-machine').selectOption(machineNo);
  await page.getByTestId('filter-title').fill(E2E_TITLE_PREFIX);
  await page.getByTestId('filter-apply').click();
  await settled();

  let removed = 0;
  // One at a time: the list re-renders after each archive, so a collected handle would go stale.
  // The ceiling is a guard against a loop that cannot make progress, not an expected count.
  for (let guard = 0; guard < 60; guard++) {
    const rows = page.getByTestId('moderation-row').filter({ hasText: E2E_TITLE_PREFIX });
    if ((await rows.count()) === 0) {
      return removed;
    }

    await rows.first().getByTestId('row-delete').click();
    await page.getByTestId('delete-comment').fill('e2e cleanup: leftover throwaway protocol');
    await page.getByTestId('delete-confirm-button').click();
    await expect(page.getByTestId('removed-notice')).toBeVisible();
    await page.getByTestId('removed-dismiss').click();
    await settled();
    removed += 1;
  }
  return removed;
}

/**
 * Corrects a protocol as the signed-in Schichtleiter, through the real API with their real token.
 *
 * <p><b>Why this is not a click, and why that is stated rather than hidden.</b> The correction path
 * lives at `PUT /api/moderation/protocols/{id}`, which since 2026-08-13 only a Schichtleiter may
 * call — and `/moderation`, the only screen with a Bearbeiten button, is guarded by
 * `roleGuard('admin')`. The role that owns the act cannot reach the screen that performs it. That
 * is a reported finding and a routing decision for Carlos; widening the route inside a UI pull
 * request would be answering a permission question nobody asked this branch.
 *
 * <p>So this is ARRANGEMENT, not verification. Everything the drill actually proves — that the
 * corrected text is what the corpus holds, and that the correction reset the approval — is asserted
 * in the browser afterwards. This suite's own rule is that an API call is not a click; it is being
 * obeyed, not sidestepped. <b>Delete this helper and restore the dialog clicks the day the route
 * opens.</b>
 *
 * <p>The token is taken from the live session rather than minted: `fetch` runs inside the page, so
 * the request is the one that browser could make, with the roles that browser holds.
 */
export async function correctAsSchichtleiter(
  page: Page,
  title: string,
  content: string,
  overrides: { machineNo?: string } = {},
): Promise<{ status: number; reason?: string }> {
  // `GET /api/protocols/mine` is the author's own list and is the only place a Schichtleiter can
  // learn a protocol id — the moderation list is admin-only, and rightly so.
  return page.evaluate(
    async ([wanted, body, machineNo]) => {
      const token = Object.keys(sessionStorage)
        .filter((key) => key.includes('access_token'))
        .map((key) => sessionStorage.getItem(key))
        .find((value): value is string => !!value && value.split('.').length === 3);
      if (!token) {
        throw new Error('no access token in the page session — is the browser signed in?');
      }
      const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

      const mine = (await (await fetch('/api/protocols/mine', { headers })).json()) as {
        id: string;
        title: string;
      }[];
      const protocol = mine.find((candidate) => candidate.title === wanted);
      if (!protocol) {
        throw new Error(`no uploaded protocol titled "${wanted}" — the filing step did not land`);
      }

      const response = await fetch(`/api/moderation/protocols/${protocol.id}`, {
        method: 'PUT',
        headers,
        body: JSON.stringify({
          machineNo,
          protocolType: 'STOERUNG',
          title: wanted,
          errorCode: '',
          content: body,
          comment: 'e2e: corrected the root cause',
        }),
      });
      if (response.ok) {
        return { status: response.status };
      }
      const failure = (await response.json()) as { reason?: string };
      return { status: response.status, reason: failure.reason };
    },
    [title, content, overrides.machineNo ?? E2E_MACHINE] as const,
  );
}

/** Signs out through the confirmation dialog, so a test leaves no session behind. */
export async function signOut(page: Page): Promise<void> {
  await page.getByTestId('sign-out').click();
  await page.getByTestId('sign-out-confirm').click();
  await expect(page.getByTestId('sign-in')).toBeVisible();
}

/**
 * The WCAG contrast ratio between an element's text colour and the background it is actually
 * painted on.
 *
 * <p><b>The background is RESOLVED, not read.</b> `getComputedStyle(el).backgroundColor` on a text
 * element is almost always `rgba(0,0,0,0)`, so the value that matters is the first ancestor with a
 * non-transparent fill — which is precisely why #47's 1.09:1 heading survived every check that
 * looked at one element in isolation.
 */
export async function contrastRatio(page: Page, selector: string): Promise<number> {
  return page.evaluate((sel) => {
    const parse = (c: string) => {
      const m = c.match(/rgba?\(([^)]+)\)/);
      if (!m) return null;
      const p = m[1].split(/[,\s/]+/).filter(Boolean).map(Number);
      return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
    };
    const luminance = ({ r, g, b }: { r: number; g: number; b: number }) => {
      const f = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
      };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };

    const el = document.querySelector(sel);
    if (!el) throw new Error(`contrastRatio: no element matches ${sel}`);

    const fg = parse(getComputedStyle(el).color);
    if (!fg) throw new Error(`contrastRatio: could not read a colour from ${sel}`);

    let node: Element | null = el;
    let bg = { r: 255, g: 255, b: 255, a: 1 };
    while (node) {
      const candidate = parse(getComputedStyle(node).backgroundColor);
      if (candidate && candidate.a > 0) {
        bg = candidate;
        break;
      }
      node = node.parentElement;
    }

    const l1 = luminance(fg);
    const l2 = luminance(bg);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  }, selector);
}

/** The same reading, plus the colours, for a failure message that says what to fix. */
export async function contrastReport(page: Page, selector: string) {
  const ratio = await contrastRatio(page, selector);
  const colours = await page.evaluate((sel) => {
    const el = document.querySelector(sel)!;
    let node: Element | null = el;
    let background = 'rgba(0, 0, 0, 0)';
    while (node) {
      const bg = getComputedStyle(node).backgroundColor;
      if (bg && !/rgba\(0, 0, 0, 0\)|transparent/.test(bg)) {
        background = bg;
        break;
      }
      node = node.parentElement;
    }
    return { color: getComputedStyle(el).color, background };
  }, selector);
  return { ratio, ...colours };
}

/**
 * The test object, extended with one thing: a preflight that turns "the stack is not running" into
 * a sentence instead of a wall of timeouts.
 *
 * <p>Worth the fixture because the failure it explains is the most common one this suite will ever
 * produce, and its natural symptom — a 30 s wait for a username that never appears — reads like a
 * broken test rather than a missing backend.
 */
export const test = base.extend<{ stack: void }>({
  stack: [
    async ({ request }, use) => {
      const problems: string[] = [];

      try {
        const health = await request.get('/api/health');
        if (!health.ok()) problems.push(`GET /api/health answered ${health.status()}`);
      } catch (error) {
        problems.push(`the backend is unreachable through the dev-server proxy (${error})`);
      }

      try {
        const realm = await request.get(`${KEYCLOAK_URL}/realms/maintenance`);
        if (!realm.ok()) problems.push(`Keycloak realm "maintenance" answered ${realm.status()}`);
      } catch (error) {
        problems.push(`Keycloak is unreachable at ${KEYCLOAK_URL} (${error})`);
      }

      if (problems.length) {
        throw new Error(
          `The e2e stack is not ready, so nothing below would be a real result:\n` +
            problems.map((p) => `  - ${p}`).join('\n') +
            `\n\nStart it as described in frontend/e2e/README.md.`,
        );
      }

      await use();
    },
    { auto: true },
  ],
});

export { expect };
