/**
 * The production guard.
 *
 * THIS SUITE WRITES. It uploads protocols, edits them and archives them, and every one of those is
 * an audited, irreversible act in the application's own terms — an archive has no restore BY DESIGN
 * (ADR-006). Pointed at production it would not corrupt anything, which is exactly what makes it
 * dangerous: it would quietly add rows to a real corpus and real entries to a real moderation
 * ledger, and there would be nothing to undo them with.
 *
 * So the URLs are checked rather than trusted, and checked at the EARLIEST possible moment. This
 * module is imported by `playwright.config.ts` at the top level, so it runs while Playwright is
 * still reading the config — before a browser is launched, before a fixture is built, before the
 * dev server is started. A misconfigured run does not start and then stop; it never starts.
 *
 * Loopback only, and by HOSTNAME rather than by "does it look like production". A deny-list of
 * known production hosts is the wrong shape: it fails open for every host nobody thought of, and
 * the one it fails open on is the one someone typed by mistake. An allow-list of three literal
 * loopback names fails CLOSED for everything else, including a staging host, a tunnel, a LAN IP and
 * a typo.
 */

/** The only hosts this suite may ever talk to. */
const ALLOWED_HOSTS = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);

export class ProductionGuardError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProductionGuardError';
  }
}

/**
 * Returns the URL unchanged if it is loopback, and throws otherwise.
 *
 * @param label which URL this is, so the failure says which variable to fix
 * @param value the configured URL
 */
export function requireLoopback(label: string, value: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new ProductionGuardError(
      `${label} is not a valid URL: ${JSON.stringify(value)}. ` +
        `The e2e suite refuses to run against an address it cannot parse.`,
    );
  }

  if (!ALLOWED_HOSTS.has(url.hostname)) {
    throw new ProductionGuardError(
      `REFUSING TO RUN: ${label} points at "${url.hostname}", which is not loopback.\n` +
        `\n` +
        `This suite uploads, edits and ARCHIVES protocols. An archive has no restore by design\n` +
        `(ADR-006), so a run against a shared or production stack would leave rows in a real\n` +
        `corpus and entries in a real moderation ledger with nothing to undo them with.\n` +
        `\n` +
        `Allowed hosts: ${[...ALLOWED_HOSTS].join(', ')}\n` +
        `Configured   : ${value}\n` +
        `\n` +
        `If you meant to point at a local stack on another port, keep the host and change the\n` +
        `port. There is deliberately no flag to switch this off.`,
    );
  }

  return value;
}

/** Where the application under test is served. Overridable by port, never by host. */
export const BASE_URL = requireLoopback(
  'E2E_BASE_URL',
  process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
);

/** Keycloak, checked separately: the login flow leaves the app's origin for the auth server's. */
export const KEYCLOAK_URL = requireLoopback(
  'E2E_KEYCLOAK_URL',
  process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8081',
);
