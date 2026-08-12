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

    toHaveScreenshot: {
      /*
       * WHY A RATIO AND NOT A PIXEL COUNT. A baseline of a full page is ~1.2 million pixels, so a
       * fixed allowance means something different on a screenshot of a table than on one of a login
       * card. A ratio scales with what is being compared.
       *
       * 0.002 — two pixels in every thousand. The number is chosen from what each end costs:
       *
       *   Too tight (0): antialiasing on a single glyph edge reddens the job, the team learns the
       *   visual check is noise, and it gets ignored. That is the failure mode this whole PR is
       *   trying to avoid, and it is worse than having no check.
       *
       *   Too loose (0.01+): 12,000 pixels is a whole component moved or a colour swapped. #46's
       *   defect — a header row welded to the list beneath it — was a few hundred pixels of white
       *   space. A check that cannot see it is decoration.
       *
       * 0.002 is ~2,400 pixels at this viewport: comfortably more than glyph noise, comfortably
       * less than any of the four design defects v1.1 shipped. `threshold` is per-pixel colour
       * tolerance in YIQ space; 0.2 is Playwright's default and is about right for text edges.
       */
      maxDiffPixelRatio: 0.002,
      threshold: 0.2,
      // A moving progress bar or a blinking caret would make every baseline a coin toss.
      animations: 'disabled',
      caret: 'hide',
      // Fonts must be loaded before anything is measured, or the first screenshot of a run captures
      // a fallback face and every later one captures the real one.
      scale: 'css',
    },
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

  /*
   * ONE BASELINE PER TEST, not one per platform.
   *
   * Playwright's default suffixes snapshot names with the OS, which quietly invites a repository to
   * hold a linux set and a darwin set and a win32 set of the same picture — three files that drift
   * apart and two of which nobody ever looks at. There is exactly one authority here: the
   * `mcr.microsoft.com/playwright` container, used both to generate baselines locally and to run
   * them in CI (see e2e/README.md). A baseline taken anywhere else is not a second opinion, it is
   * a wrong answer, and this template makes that impossible to commit by accident.
   */
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',

  // Starts `ng serve` unless one is already up, so a developer with a dev server running does not
  // get a second one on a different port. The backend, Keycloak and Postgres are NOT started here —
  // see e2e/README.md for why the environment decision stops at the frontend.
  //
  // E2E_REUSE_SERVER exists for the containerised visual run: Playwright is inside the container and
  // the dev server is on the host, so there is nothing for the container to start and everything to
  // reuse — but `CI` is set in that job, which would otherwise turn reuse off.
  webServer: {
    command: 'npm start',
    url: BASE_URL,
    reuseExistingServer: !process.env['CI'] || process.env['E2E_REUSE_SERVER'] === '1',
    timeout: 180_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
