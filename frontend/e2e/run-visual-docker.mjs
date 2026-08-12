/**
 * Runs the visual suite inside the pinned Playwright container — the ONLY environment allowed to
 * produce or check a baseline.
 *
 * WHY A SCRIPT AND NOT A ONE-LINE npm COMMAND. The invocation needs an absolute path to mount, and
 * the shell that would build it differs per machine: `$(pwd)` in bash, `%cd%` in cmd, and on Git
 * Bash for Windows a POSIX path that Docker rejects outright. Node knows the real path on every
 * platform, so the command is written once here instead of three times in package.json and wrongly
 * in at least one of them.
 *
 * WHY THE CONTAINER AT ALL. Font rendering is a property of the machine. The same page screenshotted
 * on Windows and on a GitHub runner differs on nearly every glyph edge, so a baseline generated on a
 * developer's desktop is a permanently red CI job. Pinning one image makes the question "what does
 * this look like" have a single answer.
 *
 *   node e2e/run-visual-docker.mjs                 # compare against the baselines
 *   node e2e/run-visual-docker.mjs --update        # regenerate them, deliberately
 */

import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

/** Pinned to the @playwright/test version in package.json. Bump both together, never one. */
const IMAGE = 'mcr.microsoft.com/playwright:v1.56.0-noble';

const frontendDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const update = process.argv.includes('--update');

/*
 * The bridge exists only for Docker Desktop, where "host networking" is the Linux VM's network and
 * a dev server on the developer's own machine is not in it. On Linux it is a no-op worth keeping in
 * the command rather than branching on the platform: one command that works everywhere is easier to
 * document than two that each work somewhere. See e2e/host-bridge.mjs.
 */
// `&` already terminates the command, so the backgrounded bridge is NOT followed by a semicolon.
const inner =
  'node e2e/host-bridge.mjs 4200 >/dev/null 2>&1 & sleep 1; ' +
  `npx playwright test --grep @visual ${update ? '--update-snapshots ' : ''}--reporter=list`;

const args = [
  'run',
  '--rm',
  // The suite's own guard refuses any base URL that is not loopback, so the container has to reach
  // the application ON loopback — host networking plus the bridge above, never a rewritten host.
  '--network',
  'host',
  '-v',
  `${frontendDir}:/work`,
  '-w',
  '/work',
  // CI=1 gives the same reporter and retry behaviour the pipeline uses, so a local run and a CI run
  // disagree about pixels only when the pixels actually differ.
  '-e',
  'CI=1',
  '-e',
  'E2E_REUSE_SERVER=1',
  IMAGE,
  'bash',
  '-lc',
  inner,
];

console.log(`${update ? 'REGENERATING' : 'checking'} baselines in ${IMAGE}`);
if (update) {
  console.log(
    'Remember: a baseline records what the app LOOKS LIKE, not what it should look like.\n' +
      'Regenerate only when you meant to change the design, and commit the new PNGs in the same\n' +
      'pull request as the change that caused them.',
  );
}

const result = spawnSync('docker', args, { stdio: 'inherit' });
if (result.error) {
  console.error(
    `\nCould not start docker: ${result.error.message}\n` +
      'The visual suite runs in a container by design — see frontend/e2e/README.md.',
  );
  process.exit(1);
}
process.exit(result.status ?? 1);
