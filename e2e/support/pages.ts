import { expect, Page } from '@playwright/test';
import { Identity } from './run';

/** Signs in on the Keycloak page (theme event-platform): the field ids are Keycloak's own. */
export async function signInOnKeycloak(page: Page, identity: Identity): Promise<void> {
  await expect(page.getByRole('heading', { name: 'Connexion' })).toBeVisible();
  await page.locator('#username').fill(identity.email);
  await page.locator('#password').fill(identity.password);
  await page.locator('#kc-login').click();
}

/** First sign-in: the welcome page asks for the display name, then goes back to the page asked. */
export async function completeWelcome(page: Page, identity: Identity): Promise<void> {
  await expect(page.getByRole('heading', { name: 'Bienvenue !' })).toBeVisible();
  await page.getByLabel('Nom complet').fill(identity.fullName);
  await page.getByRole('button', { name: 'Continuer vers les événements' }).click();
  await expect(page).not.toHaveURL(/\/bienvenue/);
}

/** A date-time input value ("yyyy-MM-ddTHH:mm"), in the browser's time zone, some days from now. */
export function localDateTimeIn(days: number, hours = 18): string {
  const date = new Date(Date.now() + days * 86_400_000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(hours)}:00`;
}
