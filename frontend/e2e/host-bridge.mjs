/**
 * A loopback bridge from inside the Playwright container to a dev server on the developer's machine.
 *
 * ONLY NEEDED ON DOCKER DESKTOP — that is, on Windows and macOS, and never in CI. On Linux
 * `--network host` really is the host's network namespace, so the dev server is already at
 * localhost and this script does nothing that is not already true. On Docker Desktop, "host" means
 * the Linux VM Docker runs inside: a container there can see other containers' published ports at
 * localhost, but a process listening on the developer's own machine is somewhere else entirely.
 *
 * WHY NOT JUST POINT THE TESTS AT `host.docker.internal`. Because `e2e/guard.ts` refuses any base
 * URL that is not loopback, and it should. That guard is what stops this suite — which uploads,
 * edits and ARCHIVES protocols — from ever being aimed at a shared or production stack. Weakening
 * it so a screenshot can be taken would trade a safety property for a convenience, which is the
 * wrong trade in both directions. Bridging instead keeps the guard exactly as strict as it was: the
 * tests really do talk to loopback.
 *
 * WHY TCP AND NOT AN HTTP PROXY. A byte-for-byte forward leaves the request untouched, so the
 * browser's `Host: localhost:4200` header arrives unchanged. That matters: the Angular dev server
 * refuses requests carrying a Host it does not recognise (it answered 403 to `host.docker.internal`
 * during this work), and an HTTP-level proxy would have to either rewrite the header — changing what
 * the server sees — or have the dev server's host check relaxed in angular.json, which is a
 * production-adjacent configuration change made for a test. Neither is necessary at this layer.
 *
 *   node e2e/host-bridge.mjs 4200
 */

import { connect, createServer } from 'node:net';

/** Docker Desktop's stable alias for the machine the daemon is running on. */
const HOST_ALIAS = process.env.E2E_BRIDGE_TARGET ?? 'host.docker.internal';

const ports = process.argv.slice(2).map(Number).filter(Boolean);
if (ports.length === 0) {
  ports.push(4200);
}

for (const port of ports) {
  const server = createServer((downstream) => {
    const upstream = connect(port, HOST_ALIAS);
    // A half-open connection on either side ends the pair; neither error is worth a stack trace,
    // because a browser closing a socket early is normal traffic rather than a fault.
    downstream.on('error', () => upstream.destroy());
    upstream.on('error', () => downstream.destroy());
    downstream.pipe(upstream);
    upstream.pipe(downstream);
  });

  server.listen(port, '127.0.0.1', () => {
    // eslint-disable-next-line no-console
    console.log(`bridge: 127.0.0.1:${port} -> ${HOST_ALIAS}:${port}`);
  });
}
