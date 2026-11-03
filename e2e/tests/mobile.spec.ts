import { expect, test } from '@playwright/test';
import { run, storageStateOf } from '../support/run';

/** On a phone, the bottom bar replaces the top navigation: an attendee goes through the application with it. */
test.use({ storageState: storageStateOf('attendee') });

test('an attendee gets around with the bottom bar', async ({ page }) => {
  const { event } = run();
  await page.goto('/');
  const bottomBar = page.getByRole('navigation', { name: 'Navigation principale (mobile)' });
  await expect(bottomBar).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Navigation principale', exact: true })).toBeHidden();

  await page.getByLabel('Rechercher').fill(event.title);
  await page.getByRole('article', { name: event.title }).click();
  await expect(page.getByRole('heading', { name: event.title })).toBeVisible();

  await bottomBar.getByRole('link', { name: 'Inscriptions' }).click();
  await expect(page.getByRole('heading', { name: 'Mes inscriptions' })).toBeVisible();

  await bottomBar.getByRole('link', { name: 'Compte' }).click();
  await expect(page.getByRole('heading', { name: 'Mon compte' })).toBeVisible();
  await expect(page.getByLabel('Nom complet')).toHaveValue(run().identities.attendee.fullName);
});
