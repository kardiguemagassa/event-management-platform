import { test as setup } from '@playwright/test';
import { completeWelcome, signInOnKeycloak } from '../support/pages';
import { Role, run, storageStateOf } from '../support/run';

/**
 * Each role signs in once through the real login page (BFF: the browser only gets the gateway's session cookie),
 * creates its profile on the welcome page, and the session is saved for the tests.
 */
for (const role of ['attendee', 'organizer', 'admin'] as Role[]) {
  setup(`${role} signs in and creates their profile`, async ({ page }) => {
    const identity = run().identities[role];
    await page.goto('/');
    await page.getByRole('banner').getByRole('button', { name: 'Se connecter' }).click();
    await signInOnKeycloak(page, identity);
    await completeWelcome(page, identity);
    await page.context().storageState({ path: storageStateOf(role) });
  });
}
