import type { Page } from '@playwright/test';

import { expect, signIn, test } from './support';

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
    },
    {
      label: 'P2',
      protocolId: SEED.secondProtocolId,
      title: SEED.secondTitle,
      errorCode: 'E-47',
      incidentDate: '2026-04-02',
      similarity: 0.61,
    },
  ],
};

/**
 * Selects the demo machine by what a person reads, not by what the database stores.
 *
 * The search picker's option VALUES are machine UUIDs (the upload form uses machineNo instead — the
 * two views genuinely differ). Hard-coding a UUID here would bind the test to a primary key no user
 * ever sees; matching the visible label and reading the value back off the DOM binds it to the
 * thing the test is actually about.
 */
async function pickMachine(page: Page): Promise<void> {
  const picker = page.getByTestId('machine-picker');
  await expect(picker.locator('option')).not.toHaveCount(1); // the placeholder alone means no machines loaded
  const value = await picker
    .locator('option')
    .filter({ hasText: SEED.machineNo })
    .first()
    .getAttribute('value');
  expect(value, `no option for ${SEED.machineNo} in the machine picker`).toBeTruthy();
  await picker.selectOption(value!);
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

    // The regression guard the unit suite also keeps, asserted here on the real DOM: a rendered
    // source link must not carry a raw API href, because an href is the bug.
    const hrefs = await page.getByTestId('source-link').evaluateAll((nodes) =>
      nodes.map((n) => n.getAttribute('href')),
    );
    expect(hrefs.every((href) => href === null)).toBe(true);

    // Following a marker points at its card. A marker that answered a click with nothing visible is
    // the #26 bug class in a new costume.
    await page.getByTestId('citation-marker').first().click();
    await expect(page.getByTestId('source-card').first()).toHaveClass(/highlighted/);

    // And now the click that used to 401.
    await page.getByTestId('source-link').first().click();

    await expect(page.getByTestId('protocol-section').or(page.getByTestId('protocol-raw')).first())
      .toBeVisible();
    await expect(page.getByTestId('protocol-doc-title')).toContainText(SEED.title);

    // The assertion this whole file exists for.
    expect(documentResponses.length).toBeGreaterThan(0);
    for (const response of documentResponses) {
      expect(
        response.status,
        `GET ${response.url} answered ${response.status}. 401 here is #26 returning: a document ` +
          `fetched by the browser without the interceptor's Bearer token.`,
      ).toBe(200);
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
   * A SECOND DEFECT THIS SUITE FOUND IN EXISTING CODE, and the cleanest possible illustration of
   * why it exists at all.
   *
   * `dialog.ts` moves focus into the panel on open, traps Tab inside it, and hands focus back to
   * the opener on close. It is written carefully and commented carefully — "Esc closes from
   * anywhere inside the dialog, the shortcut every modal is expected to have". MEASURED IN A REAL
   * BROWSER, focus never moves: it stays on the `source-link` that opened the viewer, both at the
   * moment the dialog appears and after its content has loaded.
   *
   * The consequences follow from that one fact. The Escape handler is bound to the backdrop, so a
   * keydown from an element OUTSIDE the dialog never reaches it, and Tab is not trapped because the
   * trap lives on the same handler. A keyboard user opens a citation and cannot leave it with the
   * key every modal has taught them to press.
   *
   * There is NO unit spec for this component — `src/app/shared/dialog/` contains a template, a
   * stylesheet and a class, and no `.spec.ts`. So the behaviour was never asserted anywhere, and
   * would not have been catchable in jsdom regardless: focus and modality are things a browser
   * decides.
   *
   * NOT FIXED HERE. This PR builds the test foundation; the cause is in the dialog's effect
   * timing and belongs to whoever fixes it, with a unit spec next to it. Recorded on the v1.1.1
   * list in PROJECT-PHASES.
   *
   * `test.fail()` and not `skip`: the assertion RUNS on every CI run, and the day the dialog is
   * fixed this test goes red and demands its own deletion.
   */
  test('KNOWN DEFECT: the viewer does not take focus, so Escape cannot close it', async ({
    page,
  }) => {
    test.fail(
      true,
      'the protocol viewer leaves focus on the element that opened it, so the backdrop never ' +
        'receives the Escape keydown. Recorded as a v1.1.1 polish candidate; when it is fixed ' +
        'this test starts passing and must be deleted.',
    );

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
