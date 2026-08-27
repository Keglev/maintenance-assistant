import { expect, selectSearchMachine, signIn, test } from './support';

/**
 * ADR-011 IN A BROWSER — an offered question actually reaches a protocol.
 *
 * The claim the whole feature rests on is that every example yields Mode A. It was measured 72 of
 * 72 against the real embedding model and the retriever's own query when the file was authored,
 * and it is asserted per-question by a backend test that every example still cites a protocol in
 * the corpus. Neither of those runs the browser, and neither proves the CHAIN: chips render, a
 * click fills the box, the reader presses the button, and a grounded answer comes back.
 *
 * <p>This is that chain, end to end, through the real stack.
 *
 * <p><b>It also pins the behaviour the ADR is most specific about: a chip FILLS AND DOES NOT
 * SUBMIT.</b> A chip that submitted would produce a good answer and no understanding — the reader
 * would never see the question that worked. So the fill is asserted before the button is pressed,
 * and pressing it is a separate act here exactly as it is for a person.
 *
 * <p>WHAT THIS CANNOT PROVE, in the same spirit as exact-term.e2e.ts: the suite runs against the
 * provider stub, whose embedding is hashed character trigrams and is not bge-m3, so a PASS here is
 * not evidence about the SCORE. It is evidence about the wiring — the endpoint, the chips, the
 * fill, the submit and the render — which no backend test can reach.
 */

/** From the seeded corpus. A missing chip here is fixture drift, not a broken app. */
const SEED = {
  /** The machine the recruiter walkthrough opens on, and the one with the most examples. */
  machineNo: 'PR-03',
};

test.describe('a first-time reader is offered a question that works', () => {
  test('clicking an example chip fills the box, and the answer that follows is grounded', async ({
    page,
  }) => {
    await signIn(page, 'techniker');
    await selectSearchMachine(page, SEED.machineNo);

    // The chips arrive with the machine, not with the page: nothing is offered until there is a
    // machine to offer questions about.
    const chips = page.getByTestId('example-chip');
    await expect(
      chips.first(),
      `no example chips for ${SEED.machineNo}. Either GET /api/machines/${SEED.machineNo}/examples ` +
        'answered nothing, or the resource file does not carry this machine.',
    ).toBeVisible({ timeout: 30_000 });

    const question = await chips.first().textContent();
    expect(question?.trim()).toBeTruthy();

    await chips.first().click();

    // FILLED, AND NOT SUBMITTED (ADR-011 §2). Both halves are the assertion: the box carries the
    // question the reader can now read, and no answer has appeared.
    await expect(page.getByTestId('question-input')).toHaveValue(question!.trim());
    await expect(page.getByTestId('answer-mode-a')).toHaveCount(0);
    await expect(page.getByTestId('answer-mode-b')).toHaveCount(0);

    await page.getByTestId('ask-button').click();

    // The point of the whole feature: an offered question is a question that gets an answer with
    // sources. A Mode B here would mean the examples have drifted from the corpus they were
    // written against — which is the failure this is here to catch before a reader does.
    await expect(
      page.getByTestId('answer-mode-a'),
      `the first ${SEED.machineNo} example produced an ungrounded answer. Every example is written ` +
        'against a protocol that exists (ADR-011); if this fails, the example and the corpus have ' +
        'stopped agreeing — see ExampleQuestionsTest.',
    ).toBeVisible({ timeout: 60_000 });

    await expect(page.getByTestId('source-card').first()).toBeVisible();
  });

  test('the offered questions follow the interface language', async ({ page }) => {
    await signIn(page, 'techniker');
    await selectSearchMachine(page, SEED.machineNo);

    await expect(page.getByTestId('example-chip').first()).toBeVisible({ timeout: 30_000 });
    const german = await page.getByTestId('example-chip').first().textContent();

    // The dictionary heading is the cheap check that the block itself is the right one.
    await expect(page.getByTestId('examples')).toContainText('Beispielfragen');

    await page.getByTestId('lang-en').click();

    await expect(page.getByTestId('examples')).toContainText('Example questions');
    const english = await page.getByTestId('example-chip').first().textContent();
    expect(
      english,
      'the chips did not change with the language — both lists arrive in one response, so a ' +
        'language switch should re-render from what is already in hand.',
    ).not.toBe(german);
  });
});
