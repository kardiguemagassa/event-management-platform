import { expect, test } from '@playwright/test';
import { signInOnKeycloak } from '../support/pages';
import { run } from '../support/run';

test.describe('visitor', () => {
  test('browses and searches the catalogue without an account', async ({ page }) => {
    const { event } = run();
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Événements à venir' })).toBeVisible();

    await page.getByLabel('Rechercher').fill(event.title);
    await expect(page).toHaveURL(/[?&]q=/);
    await page.getByRole('article', { name: event.title }).click();

    await expect(page.getByRole('heading', { name: event.title })).toBeVisible();
    await expect(page.getByText('3 places restantes')).toBeVisible();
  });

  test('signs in to register, and comes back to the event', async ({ page }) => {
    const { event, identities } = run();
    await page.goto(`/evenements/${event.id}`);

    await page.getByRole('button', { name: "Se connecter pour s'inscrire" }).click();
    await expect(page).toHaveURL(/\/realms\/event-platform\//);
    await signInOnKeycloak(page, identities.attendee);

    await expect(page).toHaveURL(new RegExp(`/evenements/${event.id}$`));
    await expect(page.getByRole('button', { name: "S'inscrire" })).toBeEnabled();
  });

  test('finds the way back to the application from the login page', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('banner').getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByText('Retrouve tes inscriptions et tes événements.')).toBeVisible();

    await page.getByRole('link', { name: '‹ Retour au catalogue' }).click();
    await expect(page.getByRole('heading', { name: 'Événements à venir' })).toBeVisible();
  });
});
