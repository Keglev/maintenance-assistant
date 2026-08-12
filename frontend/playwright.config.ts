import { defineConfig, devices } from '@playwright/test';

// Imported for its side effect as much as for the values: `guard.ts` validates the configured URLs
// while this file is being read, so a run aimed at anything but loopback dies here — before a
// browser exists, before the dev server starts. See the module for why the check lives this early.
import { BASE_URL } from './e2e/guard';

/**
 * Playwright — the rendered half of this project's test stack.
 *
 * WHY IT EXISTS, in two defects (ADR-007): #26's citation links answered 401 in a browser while
 * curl with a token returned 200, because a browser-followed <a href> is a fresh navigation that
 * carries no Bearer token. #47's realm heading rendered white on a near-white canvas at 1.09:1,
 * because the element was ABSENT from the stylesheet and nothing that reads code can see an absence.
 * jsdom has no layout engine and performs no navigation, so neither was visible to `ng test`, and
 * both were found by a person looking at a screen.
 *
 * IT DOES NOT REPLACE VITEST. Logic — mode routing, role filtering, segment parsing, the clamp
 * arithmetic — stays in the 218 unit tests, which run in seconds against no stack at all. This
 * suite is expensive: it wants a database, a Keycloak and a backend. It therefore covers what only
 * a real browser can answer: whether a flow completes, and whether the result is visible.
 */
export default defineConfig({
  testDir: './e2e',
  // Only this suffix. The unit tests are `src/**/*.spec.ts` and are compiled by tsconfig.spec.json;
  // naming these `.e2e.ts` in a directory of their own means neither runner can pick up the other's
  // files even by accident, and a reader can tell which kind a file is from its name alone.
  testMatch: /.*\.e2e\.ts$/,

  // A failing e2e test is a report about the application, so it must not depend on the order tests
  // ran in. Full isolation costs wall-clock and buys the ability to trust a single red test.
  fullyParallel: false,
  workers: 1,

  // No retries locally: a test that passes on the second attempt is a test with a race in it, and
  // hiding that is how a suite becomes decoration. CI keeps one retry to absorb runner noise, and
  // a retried pass is visible in the report rather than silent.
  retries: process.env['CI'] ? 1 : 0,
  forbidOnly: !!process.env['CI'],

  reporter: process.env['CI'] ? [['github'], ['list']] : [['list']],

  timeout: 60_000,
  expect: {
    // Generous, because two assertions here wait on ASYNCHRONOUS INDEXING, which is asynchronous by
    // design (202 Accepted, then a pipeline). Every wait in this suite is still a condition — see
    // the no-sleeps note in e2e/README.md.
    timeout: 15_000,
  },

  use: {
    baseURL: BASE_URL,
    // Evidence for the failures, and nothing for the passes: a trace per failed test is what makes
    // a red CI run diagnosable without reproducing it.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  projects: [
    {
      // CHROMIUM ONLY, and that is a statement about the users rather than a shortcut: this
      // application is used from shop-floor tablets and from desktop Chrome and Edge, all three of
      // which are Chromium — adding Firefox and WebKit would triple the runtime and the download
      // for engines nobody in this deployment runs.
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // Starts `ng serve` unless one is already up, so a developer with a dev server running does not
  // get a second one on a different port. The backend, Keycloak and Postgres are NOT started here —
  // see e2e/README.md for why the environment decision stops at the frontend.
  webServer: {
    command: 'npm start',
    url: BASE_URL,
    reuseExistingServer: !process.env['CI'],
    timeout: 180_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
