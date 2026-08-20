import { expect, selectSearchMachine, signIn, test } from './support';

/**
 * G13 IN A BROWSER — the exact-term question that hybrid retrieval exists for (ADR-009).
 *
 * The defect: "Was bedeutet KOM-04?" retrieved the right protocol at RANK 1 and scored it 0.4288,
 * below the 0.55 Mode A/B gate. The reader was told nothing while the answer sat at the top of the
 * retrieved list. ADR-009 grounds an answer when a term the question spelled out appears literally
 * in a retrieved chunk, which is what this asks the running application to demonstrate.
 *
 * WHAT THIS TEST CAN PROVE, and it is more than it looks. The lexical signal is SQL over
 * `chunk.content` — it never touches an embedding — so it behaves identically whatever produced the
 * vectors. Term extraction, the ILIKE match, the gate, the source list, the citation and the render
 * are all exercised here for real, in the real app, against the real backend and the real corpus.
 *
 * WHAT IT CANNOT PROVE, stated as plainly as #51 stated its own limits. This suite runs against the
 * provider stub, whose "embedding" is hashed character trigrams and is NOT bge-m3. So:
 *
 *   - the 0.4288 that made this question fail is a bge-m3 number and cannot be reproduced here;
 *   - with trigram vectors the question may well clear the gate on similarity ALONE, because the
 *     question and the protocol share the literal string "KOM-04" and therefore share trigrams;
 *   - so a PASS here does NOT attribute the grounding to the lexical path.
 *
 * The attribution is proven where it can be: `HybridRetrievalIT` pins the SQL against a real
 * pgvector database with controlled vectors, and `RetrievalBaselineIT` measures the whole path
 * against the real provider and the real 165-protocol corpus. THIS test answers a different and
 * still necessary question — whether a shop-floor user typing an alarm code gets a grounded,
 * correctly cited answer on screen — which no integration test can answer.
 */

/** From the seeded corpus. A 404 or an empty answer here is fixture drift, not a broken app. */
const SEED = {
  machineNo: 'PR-07',
  code: 'KOM-04',
  title: 'Presse 7 in der Leitwarte ausgegraut, Anlage steht',
};

test.describe('an alarm code is answered, not shrugged at', () => {
  test('asking a bare alarm code returns a grounded answer citing the protocol that carries it', async ({
    page,
  }) => {
    await signIn(page, 'techniker');
    await selectSearchMachine(page, SEED.machineNo);

    await page.getByTestId('question-input').fill(`Was bedeutet ${SEED.code}?`);
    await page.getByTestId('ask-button').click();

    // Mode A is the assertion. Before ADR-009 this question produced the ungrounded answer, which
    // is the thing a technician cannot act on.
    await expect(
      page.getByTestId('answer-mode-a'),
      `asking "${SEED.code}" produced no grounded answer. Retrieval ranks the right protocol first ` +
        'for this question, so a Mode B here means the gate or the source list stopped agreeing ' +
        'with each other — see QueryService.labelByProtocol.',
    ).toBeVisible({ timeout: 60_000 });

    // The right protocol, not merely any protocol: grounding the answer in the wrong record would
    // be worse than refusing, because a citation makes a claim look checked (NFR-2).
    await expect(page.getByTestId('source-card').first()).toContainText(SEED.title);

    // And the claim carries its marker, which is what makes the answer checkable at all.
    await expect(page.getByTestId('citation-marker').first()).toBeVisible();
  });
});
