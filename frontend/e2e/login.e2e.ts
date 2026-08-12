import { KEYCLOAK_URL } from './guard';
import { DEMO_PASSWORD, expect, signIn, signOut, test } from './support';

/**
 * The login flow, end to end, through the real Keycloak.
 *
 * The unit suite cannot reach any of this: the round trip leaves the application's origin, and the
 * page it leaves for is rendered by a different server from a FreeMarker template and a stylesheet
 * that is not in this repository's build at all.
 */
test.describe('login', () => {
  test('the themed login page renders, and it is OUR theme', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('sign-in').click();
    await page.waitForURL(`${KEYCLOAK_URL}/**`);

    // NOT just the URL. A broken Keycloak theme falls back to stock SILENTLY — that is the whole
    // reason the deploy runbook greps the log — so the assertion has to name something only this
    // theme puts on the page. `.wa-back-link` and `.wa-demo` exist in exactly one file on earth:
    // docker/keycloak/themes/wartungsassistent/login/footer.ftl.
    await expect(page.locator('.wa-back-link')).toBeVisible();
    await expect(page.locator('[data-testid="wa-demo-accounts"]')).toBeVisible();

    // And the realm heading, which is the element #47 was about.
    await expect(page.locator('#kc-header-wrapper')).toBeVisible();
  });

  test('a demo user signs in and lands on their own home', async ({ page }) => {
    await signIn(page, 'techniker');

    // The shop floor lands in search. This is the same routing decision that put an admin in front
    // of "Maschinenliste nicht verfügbar" in production once (#38), which is why it is asserted
    // per role rather than assumed.
    await expect(page).toHaveURL(/\/search$/);
    await expect(page.getByTestId('header-role')).toBeVisible();
    await expect(page.getByTestId('machine-picker')).toBeVisible();
  });

  test('a wrong password is refused and stays on the themed page', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('sign-in').click();
    await page.waitForURL(`${KEYCLOAK_URL}/**`);

    await page.locator('#username').fill('techniker');
    await page.locator('#password').fill(`not-${DEMO_PASSWORD}`);
    await page.locator('#kc-login').click();

    // Keycloak 26.7 renders this as inline helper text under the field, NOT as a .pf-v5-c-alert —
    // a detail measured in #47 and worth locking, because the theme still carries an alert rule
    // that this flow never triggers.
    await expect(page.locator('.kc-feedback-text')).toBeVisible();
    // Still themed after the failure: the error page is a page too.
    await expect(page.locator('.wa-back-link')).toBeVisible();
  });

  test('sign-out ends the session and returns to the landing page', async ({ page }) => {
    await signIn(page, 'techniker');
    await signOut(page);

    await expect(page.getByTestId('signed-out-notice')).toBeVisible();
  });
});
