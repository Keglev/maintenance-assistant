import { expect, signIn, test } from './support';

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
  test('an admin lands in Protokollverwaltung, not in search', async ({ page }) => {
    await signIn(page, 'admin');

    await expect(page).toHaveURL(/\/moderation$/);
    await expect(page.getByTestId('moderation-table').or(page.getByTestId('moderation-empty')))
      .toBeVisible();

    // The live defect in one assertion: the admin must never see the machine-list error, because
    // the admin must never be sent to the view that asks for it.
    await expect(page.getByTestId('machines-error')).toHaveCount(0);

    // Search is not a view they can use at all, so it is not offered.
    await expect(page.getByTestId('nav-search')).toHaveCount(0);
    await expect(page.getByTestId('nav-moderation')).toBeVisible();
  });

  test('a shop-floor role cannot reach /moderation, by URL either', async ({ page }) => {
    await signIn(page, 'techniker');

    // Typing the URL, not clicking a link that is not there — a guard that only hides its nav entry
    // is decoration.
    await page.goto('/moderation');

    await expect(page).not.toHaveURL(/\/moderation$/);
    await expect(page).toHaveURL(/\/search$/);
    await expect(page.getByTestId('moderation-table')).toHaveCount(0);
    await expect(page.getByTestId('nav-moderation')).toHaveCount(0);
  });

  test('a technician may not reach the upload view', async ({ page }) => {
    await signIn(page, 'techniker');
    await page.goto('/upload');

    // Uploading is the Schichtleiter's, by decision: the protocol is filed at the end of the shift
    // by the person responsible for it.
    await expect(page).not.toHaveURL(/\/upload$/);
    await expect(page.getByTestId('nav-upload')).toHaveCount(0);
  });

  test('a Schichtleiter may upload, and still may not moderate', async ({ page }) => {
    await signIn(page, 'schichtleiter');

    await expect(page.getByTestId('nav-upload')).toBeVisible();
    await page.getByTestId('nav-upload').click();
    await expect(page.getByTestId('upload-machine')).toBeVisible();

    // Moderation belongs to the role that CANNOT write (ADR-006): a tool the author of a bad
    // protocol could reach would let them remove the evidence.
    await page.goto('/moderation');
    await expect(page).not.toHaveURL(/\/moderation$/);
  });
});
