#!/usr/bin/env node
/**
 * Render every Mermaid source (*.mmd) under docs/ to SVG, next to its source.
 *
 *   node docs/scripts/generate-diagrams.mjs          # render all
 *   node docs/scripts/generate-diagrams.mjs --check  # fail if any SVG is missing or stale
 *
 * Rendering runs through `npx -y @mermaid-js/mermaid-cli@<pinned>` (mmdc), so nothing has to be
 * installed up front — but it does need network access on first use, and a Chromium
 * download. Generated SVGs are not versioned (see .gitignore); the docs site build
 * regenerates them.
 *
 * Requires Node 18+ (uses fs/promises and structured stdio only — no dependencies).
 */

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { readdir, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const DOCS_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const CHECK_ONLY = process.argv.includes('--check');
// PIN — BUMP THIS DELIBERATELY, and diff the rendered SVGs when you do. This renderer
// produces published pages, exactly like the pandoc pin in docs-pr-check.yml and
// docs.yml, and a different mermaid-cli may render differently: the .mmd sources are
// versioned and the SVGs are NOT, so an unpinned renderer could change every diagram on
// the site without a single line of this repository changing. Unpinned until 2026-08-26
// (Part 3, C17); 11.16.0 is the version that was resolving at the time, measured with
// `npx -y @mermaid-js/mermaid-cli --version`.
const MMDC = ['-y', '@mermaid-js/mermaid-cli@11.16.0'];

/** Recursively collect *.mmd files, skipping node_modules. */
async function findMermaidSources(dir) {
  const found = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) found.push(...(await findMermaidSources(full)));
    else if (entry.isFile() && entry.name.endsWith('.mmd')) found.push(full);
  }
  return found;
}

/** mtime in ms, or 0 when the file does not exist. */
async function mtimeOf(path) {
  try {
    return (await stat(path)).mtimeMs;
  } catch {
    return 0;
  }
}

function render(source, target) {
  // Node refuses to spawn a .cmd shim without a shell on Windows, so npx runs through
  // the shell there; paths are quoted because a shell re-splits on whitespace.
  const onWindows = process.platform === 'win32';
  const quote = (value) => (onWindows ? `"${value}"` : value);

  // mermaid-cli drives a headless Chromium. CI containers run as root without a
  // user namespace, where Chromium refuses to start unless its sandbox is disabled;
  // the config file carries those flags and is ignored when absent locally.
  const puppeteerConfig = join(dirname(fileURLToPath(import.meta.url)), 'puppeteer-config.json');
  const puppeteerArgs = existsSync(puppeteerConfig)
    ? ['--puppeteerConfigFile', quote(puppeteerConfig)]
    : [];

  return new Promise((done, fail) => {
    const child = spawn(
      onWindows ? 'npx.cmd' : 'npx',
      [
        ...MMDC,
        '--input', quote(source),
        '--output', quote(target),
        '--backgroundColor', 'transparent',
        ...puppeteerArgs,
      ],
      { stdio: 'inherit', shell: onWindows },
    );
    child.on('error', fail);
    child.on('close', (code) =>
      code === 0 ? done() : fail(new Error(`mmdc exited with code ${code}`)),
    );
  });
}

const sources = await findMermaidSources(DOCS_DIR);

if (sources.length === 0) {
  console.log('No .mmd files found under docs/ — nothing to do.');
  process.exit(0);
}

const stale = [];
for (const source of sources) {
  const target = source.replace(/\.mmd$/, '.svg');
  if ((await mtimeOf(target)) < (await mtimeOf(source))) stale.push({ source, target });
}

if (CHECK_ONLY) {
  if (stale.length > 0) {
    console.error('Diagrams out of date:');
    for (const { source } of stale) console.error(`  ${source}`);
    console.error('\nRun: node docs/scripts/generate-diagrams.mjs');
    process.exit(1);
  }
  console.log(`All ${sources.length} diagram(s) up to date.`);
  process.exit(0);
}

if (stale.length === 0) {
  console.log(`All ${sources.length} diagram(s) already up to date.`);
  process.exit(0);
}

for (const { source, target } of stale) {
  console.log(`Rendering ${source} -> ${target}`);
  await render(source, target);
}

console.log(`Rendered ${stale.length} of ${sources.length} diagram(s).`);
