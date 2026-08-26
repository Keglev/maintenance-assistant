import type { Page } from '@playwright/test';

import { expect, selectSearchMachine, signIn, test } from './support';

/**
 * THE #26 REGRESSION, in a browser.
 *
 * The defect: a citation's source link was a plain `<a href="/api/protocols/…/document">`. A
 * browser-followed href is a fresh navigation — it never reaches Angular's HTTP interceptor, so it
 * carries no Bearer token, and the backend is a stateless JWT resource server with no cookie
 * fallback. Every click on a citation answered 401 in production while the verification that had
 * been run — curl with a token — answered 200. "An API call is not a click."
 *
 * The unit suite guards the SHAPE of the fix (search.spec.ts asserts no rendered source link
 * carries a raw /api href). It cannot guard the BEHAVIOUR, because jsdom performs no navigation and
 * makes no request. This test asserts the status the browser actually received.
 *
 * WHY THE ANSWER IS STUBBED AND THE DOCUMENT IS NOT — this is the important line. `POST /api/query`
 * is a paid call to a shared-capacity provider with a measured 38.9 s worst case (ADR-002), and its
 * output is a language model's prose. Putting that in the critical path would buy nothing for this
 * defect and would cost money and flakiness on every run. So the ANSWER is canned, and everything
 * the defect actually lived in stays real: the real app, the real token, the real interceptor, the
 * real backend, the real file on disk. The stub decides what the answer says; the browser still has
 * to fetch the document itself.
 */

/**
 * A protocol from the seeded corpus — the E-47 demo case on Presse 3, the one the whole project
 * uses as its worked example. The ids are deterministic in the seed data. If this test fails with a
 * 404, the fixture has drifted from the corpus rather than the application being broken; re-read
 * the id from the seed and update it here.
 */
const SEED = {
  machineNo: 'PR-03',
  protocolId: '0f9c5b02-0000-4000-8000-000000000001',
  title: 'E-47 Druckabfall im Presshub',
  secondProtocolId: '0f9c5b02-0000-4000-8000-000000000002',
  secondTitle: 'E-47 nach Programmwechsel auf Teil 4711',
};

/** A Mode A answer of the exact shape the backend returns, with two citations. */
const STUBBED_ANSWER = {
  mode: 'A',
  language: 'de',
  answer:
    'Der Druckabfall im Presshub entsteht meist am Druckbegrenzungsventil [P1]. ' +
    'Nach einem Programmwechsel ist zusätzlich der Parametersatz zu prüfen [P2].',
  claims: [
    { text: 'Druckbegrenzungsventil prüfen', label: 'P1' },
    { text: 'Parametersatz nach Programmwechsel prüfen', label: 'P2' },
  ],
  citations: [
    {
      label: 'P1',
      protocolId: SEED.protocolId,
      title: SEED.title,
      errorCode: 'E-47',
      incidentDate: '2026-03-14',
      similarity: 0.69,
      // The seeded 150 are born approved (`system:corpus-seed`), so this is what the backend really
      // sends for them. A stub missing the field would render an unapproved marker on a source the
      // application would never mark, and this file's job is to be the shape of a real answer.
      approved: true,
    },
    {
      label: 'P2',
      protocolId: SEED.secondProtocolId,
      title: SEED.secondTitle,
      errorCode: 'E-47',
      incidentDate: '2026-04-02',
      similarity: 0.61,
      approved: true,
    },
  ],
};

/** Selects the demo machine by the label a person reads. Shared — see `selectSearchMachine`. */
async function pickMachine(page: Page): Promise<void> {
  await selectSearchMachine(page, SEED.machineNo);
}

/** What currently has focus, named the way the rest of this suite names things. */
async function activeTestId(page: Page): Promise<string> {
  return page.evaluate(
    () =>
      document.activeElement?.getAttribute('data-testid') ??
      document.activeElement?.tagName ??
      '(nothing)',
  );
}

test.describe('Mode A citation click-through', () => {
  test('a citation opens its protocol, and the document does NOT 401', async ({ page }) => {
    // Every response the browser received for a document fetch. This — not a curl, not a mock — is
    // what #26 got wrong.
    const documentResponses: { status: number; url: string }[] = [];
    page.on('response', (response) => {
      if (/\/api\/protocols\/[^/]+\/document/.test(response.url())) {
        documentResponses.push({ status: response.status(), url: response.url() });
      }
    });

    await signIn(page, 'techniker');

    await page.route('**/api/query', async (route) => {
      await route.fulfill({ json: STUBBED_ANSWER });
    });

    await pickMachine(page);
    await page.getByTestId('question-input').fill('Warum fällt der Druck im Presshub ab?');
    await page.getByTestId('ask-button').click();

    // Mode A, with its sources.
    await expect(page.getByTestId('answer-mode-a')).toBeVisible();
    await expect(page.getByTestId('source-card')).toHaveCount(2);

    // v1.2: every source card says whether the protocol behind it was reviewed. On a REAL rendered
    // card, because that is where a marker can be present in the DOM and invisible on the screen —
    // clipped by the collapsed layout, or drawn behind the card's own edge.
    await expect(page.getByTestId('approval-state').first()).toBeVisible();
    await expect(page.getByTestId('approval-state').first()).toHaveAttribute(
      'data-approval',
      'APPROVED',
    );

    // The regression guard the unit suite also keeps, asserted here on the real DOM: a rendered
    // source link must not carry a raw API href, because an href is the bug.
    const hrefs = await page
      .getByTestId('source-link')
      .evaluateAll((nodes) => nodes.map((n) => n.getAttribute('href')));
    expect(hrefs.every((href) => href === null)).toBe(true);

    // Following a marker points at its card. A marker that answered a click with nothing visible is
    // the #26 bug class in a new costume.
    await page.getByTestId('citation-marker').first().click();
    await expect(page.getByTestId('source-card').first()).toHaveClass(/highlighted/);

    // And now the click that used to 401.
    await page.getByTestId('source-link').first().click();

    await expect(
      page.getByTestId('protocol-section').or(page.getByTestId('protocol-raw')).first(),
    ).toBeVisible();
    await expect(page.getByTestId('protocol-doc-title')).toContainText(SEED.title);

    // The assertion this whole file exists for.
    expect(documentResponses.length).toBeGreaterThan(0);
    for (const response of documentResponses) {
      // THE MESSAGE DISTINGUISHES THE TWO WAYS THIS GOES RED, because they demand opposite
      // reactions and a bare "expected 200" would send the reader looking in the wrong place.
      //
      // 401 is the #26 defect returning — the application is broken and the fix is in the app.
      // 404 is FIXTURE DRIFT — the application is fine and this file is out of date, because the
      // seed ids below no longer exist in the corpus.
      //
      // Resolving the ids at run time was considered and rejected: a technician has no endpoint
      // that lists a machine's protocols (by design — that view belongs to the admin), so a lookup
      // would mean a second sign-in and a role switch inside a test about a technician clicking a
      // citation. Naming the failure costs one sentence and misleads nobody.
      const diagnosis =
        response.status === 404
          ? `FIXTURE DRIFT: the seed protocol ${SEED.protocolId} is not in this corpus. The ` +
            `application is not broken — update SEED in this file from the seed data.`
          : `401 here is #26 returning: a document fetched by the browser without the ` +
            `interceptor's Bearer token.`;
      expect(response.status, `GET ${response.url} answered ${response.status}. ${diagnosis}`).toBe(
        200,
      );
    }
  });

  test('the viewer renders the stored protocol, not an empty dialog', async ({ page }) => {
    await signIn(page, 'techniker');
    await page.route('**/api/query', (route) => route.fulfill({ json: STUBBED_ANSWER }));

    await pickMachine(page);
    await page.getByTestId('question-input').fill('Warum fällt der Druck im Presshub ab?');
    await page.getByTestId('ask-button').click();
    await page.getByTestId('source-link').first().click();

    // A 200 that renders nothing is still a broken citation. The viewer parses
    // Symptom/Ursache/Massnahme and falls back to the raw text, so either is a pass — what is not a
    // pass is an empty dialog.
    const body = page.getByTestId('protocol-body');
    await expect(body).toBeVisible();
    await expect(body).not.toBeEmpty();
    await expect(page.getByTestId('protocol-failure')).toHaveCount(0);
  });

  /**
   * THE SUITE'S FIRST CAUGHT-AND-CLOSED DEFECT — and the reason it was written.
   *
   * This test arrived in #50 marked `test.fail()`, documenting something real: the viewer never
   * took focus. It stayed on the `source-link` that opened it, so the Escape handler on the
   * backdrop never saw a key and the tab trap never ran. A keyboard user could open a citation and
   * not leave it with the key every modal has taught them to press.
   *
   * The cause, found in this PR: `focusables()` matched `button` without excluding DISABLED ones,
   * and the viewer's first projected control is a Download button that is disabled until the
   * document arrives. `focus()` on a disabled element does nothing and reports nothing, so the
   * dialog opened with focus outside itself and stayed silent about it.
   *
   * Fixed in `dialog.ts` (disabled controls excluded, the panel itself as the fallback focus
   * target) with the unit spec the component had never had. THE MARKER IS GONE: this is a plain
   * passing test now, which is what a `test.fail()` is for — it runs the strict assertion every
   * time and demands its own retirement the day the defect closes.
   */
  test('the viewer takes focus, and Escape closes it', async ({ page }) => {
    await signIn(page, 'techniker');
    await page.route('**/api/query', (route) => route.fulfill({ json: STUBBED_ANSWER }));

    await pickMachine(page);
    await page.getByTestId('question-input').fill('Warum fällt der Druck im Presshub ab?');
    await page.getByTestId('ask-button').click();
    await page.getByTestId('source-link').first().click();

    // Measured twice, because the two readings answer different questions: "did the dialog ever
    // take focus" and "did it keep focus when its body re-rendered after the fetch". Both currently
    // report the opener, which settles it as the former.
    await expect(page.getByTestId('protocol-dialog')).toBeVisible();
    const focusOnOpen = await activeTestId(page);
    await expect(page.getByTestId('protocol-body')).toBeVisible();
    const focusAfterLoad = await activeTestId(page);

    const focusInsideDialog = await page.evaluate(() => {
      const dialog = document.querySelector('[data-testid="protocol-dialog"]');
      return !!dialog && dialog.contains(document.activeElement);
    });
    expect(
      focusInsideDialog,
      `the viewer never took focus — it is on "${focusOnOpen}" when the dialog opens and ` +
        `"${focusAfterLoad}" after its content loads, both outside the panel`,
    ).toBe(true);

    // The consequence, asserted rather than assumed.
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('protocol-backdrop')).toHaveCount(0);
  });
});
