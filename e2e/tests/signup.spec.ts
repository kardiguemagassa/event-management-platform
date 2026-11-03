import { expect, test } from '@playwright/test';
import { completeWelcome } from '../support/pages';
import { run } from '../support/run';

test('creates an account, explained in French when the password is too short', async ({ page }) => {
  const { signup } = run();
  await page.goto('/');
  await page.getByRole('banner').getByRole('button', { name: 'Se connecter' }).click();
  await page.getByRole('link', { name: 'Créer un compte' }).click();
  await expect(page.getByRole('heading', { name: 'Créer un compte' })).toBeVisible();

  await page.locator('#email').fill(signup.email);
  await page.locator('#password').fill('court');
  await page.locator('#password-confirm').fill('court');
  await page.locator('#firstName').fill('Nouvelle');
  await page.locator('#lastName').fill('Inscrite');
  await page.getByRole('button', { name: 'Créer un compte' }).click();
  await expect(page.getByText('12 caractères au minimum.')).toBeVisible();

  await page.locator('#password').fill(signup.password);
  await page.locator('#password-confirm').fill(signup.password);
  await page.getByRole('button', { name: 'Créer un compte' }).click();

  // Back in the application, the first sign-in asks for the display name, pre-filled from the account.
  await expect(page.getByLabel('Nom complet')).toHaveValue('Nouvelle Inscrite');
  await completeWelcome(page, signup);
  await expect(page.getByRole('heading', { name: 'Événements à venir' })).toBeVisible();
});
