import { beforeEach } from 'vitest';

/**
 * Starts every test with empty browser storage, whatever ran before it in the same worker.
 *
 * WHY: the Angular unit-test builder runs Vitest with `isolate: false`, so the spec files a worker
 * runs share one jsdom window and its storage, and which file runs before which changes from run to
 * run. The services persist the language, theme and font scale to localStorage, so a test could
 * inherit a choice another file left behind, and coverage hit counts differed between identical runs.
 */
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});
