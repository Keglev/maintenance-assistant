import {
  E2E_MACHINE,
  E2E_TITLE_PREFIX,
  expect,
  fileProtocol,
  signIn,
  sweepThrowaways,
  test,
} from './support';

/** The same shape every throwaway in this suite uses: symptom, cause, action. */
const BODY = [
  'Symptom: Foerderband stoppt unregelmaessig im Automatikbetrieb.',
  'Ursache: Endschalter am Umlenkrad locker.',
  'Massnahme: Endschalter nachgezogen und Funktion geprueft.',
].join('\n');

/**
 * Who may reach what.
 *
 * The unit suite tests `roleGuard` and `homePath` as functions. This tests the thing that actually
 * broke in production (#38): every piece behaved exactly as specified and an admin still landed on
 * a view full of errors, because the defect was in the ROUTING — /home redirected everyone to
 * /search, and /search opens with a call an admin is correctly refused. A guard that is right and a
 * landing page that is wrong add up to a broken login, and only a real navigation shows it.
 */
test.describe('role gating', () => {
  /*
   * WHAT THIS SUITE CREATES, IT ARCHIVES — the same rule approval.e2e.ts follows, and it arrived
   * here on 2026-08-28 with the first test in this file that files anything. Through the
   * application with a reason, never SQL: a cleanup that reached around the moderation ledger
   * would still pass if that ledger were broken.
   */
  test.afterEach(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await signIn(page, 'admin');
      await sweepThrowaways(page, E2E_MACHINE);
    } finally {
      await context.close();
    }
  });

  test('an admin lands in Protokollverwaltung, not in search', async ({ page }) => {
    await signIn(page, 'admin');

    await expect(page).toHaveURL(/\/moderation$/);
    await expect(
      page.getByTestId('moderation-table').or(page.getByTestId('moderation-empty')),
    ).toBeVisible();

    // The live defect in one assertion: the admin must never see the machine-list error, because
    // the admin must never be sent to the view that asks for it.
    await expect(page.getByTestId('machines-error')).toHaveCount(0);

    // Search is not a view they can use at all, so it is not offered.
    await expect(page.getByTestId('nav-search')).toHaveCount(0);
    await expect(page.getByTestId('nav-moderation')).toBeVisible();
  });

  test('a role with no job there cannot reach /moderation, by URL either', async ({ page }) => {
    // The Techniker, who writes and never corrects. The Schichtleiter came off this test on
    // 2026-08-14 and has one of their own below; a guard that refuses everybody is not the rule,
    // "only the two roles with a job here" is.
    await signIn(page, 'techniker');

    // Typing the URL, not clicking a link that is not there — a guard that only hides its nav entry
    // is decoration.
    await page.goto('/moderation');

    await expect(page).not.toHaveURL(/\/moderation$/);
    await expect(page).toHaveURL(/\/search$/);
    await expect(page.getByTestId('moderation-table')).toHaveCount(0);
    await expect(page.getByTestId('nav-moderation')).toHaveCount(0);
  });

  test('a technician reaches the upload view and sees the machine field', async ({ page }) => {
    await signIn(page, 'techniker');

    // FLIPPED 2026-08-28. Until then this test asserted the opposite and was right to: uploading
    // was the Schichtleiter's alone. Decision 3 of 2026-08-11 gave the Techniker the write — the
    // person standing at the machine when it is fixed is the person who knows what happened — and
    // the backend applied it in v1.2.0 while this screen did not, so the permission existed and the
    // door did not. Same shape as the Schichtleiter's correction door in 2026-08-14 below.
    await expect(page.getByTestId('nav-upload')).toBeVisible();
    await page.getByTestId('nav-upload').click();
    await expect(page).toHaveURL(/\/upload$/);
    await expect(page.getByTestId('upload-machine')).toBeVisible();

    // What did NOT move with it: correcting is still the Schichtleiter's, and a writer who could
    // fix their own protocol would end the four-eyes rule ADR-006 rests on.
    await expect(page.getByTestId('nav-moderation')).toHaveCount(0);
  });

  test('a technician files a protocol and sees it in their own uploads', async ({ page }) => {
    const title = `${E2E_TITLE_PREFIX} techniker ${Date.now()}`;

    // The other half of decision 3, and the half that was missing longest: GET /api/protocols/mine
    // was Schichtleiter-only until 2026-08-28, so a Techniker could file a protocol and had no way
    // to see whether indexing had failed. Upload answers 202, which makes this list the only place
    // that fact is visible. Reuses the suite's one upload harness rather than a second copy.
    await fileProtocol(page, title, 'techniker', E2E_MACHINE, BODY);

    await expect(
      page.getByTestId('uploads-table').locator('tr').filter({ hasText: title }),
    ).toHaveCount(1);
  });

  test('a Schichtleiter may upload, and reaches the correction view', async ({ page }) => {
    await signIn(page, 'schichtleiter');

    await expect(page.getByTestId('nav-upload')).toBeVisible();
    await page.getByTestId('nav-upload').click();
    await expect(page.getByTestId('upload-machine')).toBeVisible();

    // 2026-08-14: the corrector gets a door. Until then this test asserted the opposite, and it was
    // right to — the endpoint was admin-only. Correcting became theirs on 2026-08-13 and the screen
    // did not follow, which left the correction path with no interface for anybody.
    await expect(page.getByTestId('nav-moderation')).toBeVisible();
    await page.getByTestId('nav-moderation').click();
    await expect(page).toHaveURL(/\/moderation$/);
    await expect(
      page.getByTestId('moderation-table').or(page.getByTestId('moderation-empty')),
    ).toBeVisible();
  });

  test('the correction view gives the Schichtleiter no moderation powers', async ({ page }) => {
    // THE FENCE, ON A REAL RENDER. Opening the route handed the corrector a screen the administrator
    // built; what they must NOT find on it is the administrator's job. ADR-006's reason still holds:
    // moderation belongs to the role that cannot write, because a tool the author of a bad protocol
    // could reach would let them remove the evidence.
    await signIn(page, 'schichtleiter');
    await page.goto('/moderation');
    await expect(page.getByTestId('moderation-row').first()).toBeVisible();

    const row = page.getByTestId('moderation-row').first();
    await expect(row.getByTestId('row-edit')).toBeVisible();
    await expect(row.getByTestId('row-open')).toBeVisible();

    // Absent, not disabled — a control that exists only to refuse is noise, and a lie about the role.
    await expect(row.getByTestId('row-delete')).toHaveCount(0);
    await expect(row.getByTestId('row-approve')).toHaveCount(0);
    await expect(row.getByTestId('row-withdraw')).toHaveCount(0);
    await expect(page.getByTestId('tab-archive')).toHaveCount(0);

    // And the screen says which job it is, because "Verwaltung" would describe powers just checked
    // to be absent.
    await expect(page.locator('.page-title')).not.toHaveText(
      /Protokollverwaltung|Protocol management/,
    );
  });
});
