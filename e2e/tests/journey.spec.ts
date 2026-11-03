import { Browser, expect, Page, test } from '@playwright/test';
import { localDateTimeIn } from '../support/pages';
import { Role, run, storageStateOf } from '../support/run';

/**
 * The life of an event across the three roles, in order: the organizer creates, publishes and edits it, an attendee
 * registers, the organizer sees who registered, an administrator cancels it (moderation) and the attendee sees it.
 */
test.describe.configure({ mode: 'serial' });

const title = () => `Atelier Playwright ${run().id}`;

async function as(browser: Browser, role: Role): Promise<Page> {
  const context = await browser.newContext({ storageState: storageStateOf(role) });
  return context.newPage();
}

test('the organizer creates a draft, publishes it, then edits it', async ({ browser }) => {
  const page = await as(browser, 'organizer');
  await page.goto('/organisation');
  await page.getByRole('link', { name: 'Nouvel événement' }).click();

  await page.getByLabel('Titre').fill(title());
  await page.getByLabel('Lieu').fill('Nantes');
  await page.getByLabel('Début').fill(localDateTimeIn(30));
  await page.getByLabel('Capacité').fill('5');
  await page.getByLabel('Description').fill('Tests de bout en bout dans un vrai navigateur.');
  await page.getByRole('button', { name: 'Créer le brouillon' }).click();

  await expect(page).toHaveURL(/\/organisation\?statut=brouillons/);
  await expect(page.getByRole('status')).toContainText(`Brouillon « ${title()} » créé`);
  const row = page.getByRole('row').filter({ hasText: title() });
  await expect(row).toContainText('Brouillon');

  await row.getByRole('button', { name: 'Publier' }).click();
  await expect(page.getByRole('status')).toContainText(`« ${title()} » est publié`);

  await page.goto('/organisation');
  await page.getByRole('row').filter({ hasText: title() }).getByRole('link', { name: 'Modifier' }).click();
  await expect(page.getByRole('heading', { name: "Modifier l'événement" })).toBeVisible();
  await page.getByLabel('Lieu').fill('Lyon, Part-Dieu');
  await page.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByRole('status')).toContainText(`« ${title()} » est enregistré`);
  await expect(page.getByRole('row').filter({ hasText: title() })).toContainText('Lyon, Part-Dieu');
});

test('an attendee finds it in the catalogue and registers', async ({ browser }) => {
  const page = await as(browser, 'attendee');
  await page.goto(`/?q=${encodeURIComponent(title())}`);
  await page.getByRole('article', { name: title() }).click();

  await expect(page.getByRole('heading', { name: title() })).toBeVisible();
  await expect(page.getByText('5 places restantes')).toBeVisible();
  await page.getByRole('button', { name: "S'inscrire" }).click();

  await expect(page.getByText('Tu es inscrit à cet événement.')).toBeVisible();
  await expect(page.getByText('4 places restantes')).toBeVisible();

  await page.getByRole('navigation', { name: 'Navigation principale', exact: true }).getByRole('link', { name: 'Inscriptions' }).click();
  await expect(page.getByRole('heading', { name: 'Mes inscriptions' })).toBeVisible();
  await expect(page.getByRole('link').filter({ hasText: title() })).toContainText('Confirmée');
});

test('the organizer sees the name of the attendee', async ({ browser }) => {
  const page = await as(browser, 'organizer');
  await page.goto('/organisation');
  await page.getByRole('row').filter({ hasText: title() }).getByRole('link', { name: 'Inscrits' }).click();

  await expect(page.getByRole('heading', { name: 'Inscrits' })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: run().identities.attendee.fullName })).toContainText(
    'Confirmée',
  );
});

test('an administrator cancels it, and the attendee is told', async ({ browser }) => {
  const admin = await as(browser, 'admin');
  await admin.goto(`/administration/evenements?q=${encodeURIComponent(title())}`);
  const row = admin.getByRole('row').filter({ hasText: title() });
  await expect(row).toContainText(run().identities.organizer.fullName);

  await row.getByRole('button', { name: 'Annuler' }).click();
  const dialog = admin.getByRole('dialog');
  await expect(dialog).toContainText(`Annuler « ${title()} » ?`);
  await dialog.getByRole('button', { name: "Annuler l'événement" }).click();
  await expect(admin.getByRole('status')).toContainText(`« ${title()} » est annulé`);
  await expect(admin.getByRole('row').filter({ hasText: title() })).toContainText('Annulé');

  const attendee = await as(browser, 'attendee');
  await attendee.goto('/mes-inscriptions');
  await expect(attendee.getByRole('link').filter({ hasText: title() })).toContainText('Événement annulé');
});

test('an administrator finds the profile of the attendee', async ({ browser }) => {
  const { attendee } = run().identities;
  const page = await as(browser, 'admin');
  await page.goto('/administration/utilisateurs');
  await page.getByLabel('Nom ou e-mail').fill(attendee.email);
  await page.getByRole('button', { name: 'Rechercher' }).click();

  await expect(page.getByText('1 profil', { exact: true })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: attendee.email })).toContainText(attendee.fullName);
});
