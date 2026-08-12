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
