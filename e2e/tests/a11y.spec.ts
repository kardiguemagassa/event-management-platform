import AxeBuilder from '@axe-core/playwright';
import { expect, Page, test } from '@playwright/test';
import { Role, run, storageStateOf } from '../support/run';

/**
 * Automated accessibility checks (axe-core, WCAG 2.1 A and AA rules) on the main pages of each role. A serious or
 * critical violation fails the test; the report lists each rule with the elements concerned.
 */
async function expectAccessible(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = results.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => ({
      rule: violation.id,
      impact: violation.impact,
      help: violation.help,
      elements: violation.nodes.map((node) => node.target.join(' ')).slice(0, 5),
    }));
  expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([]);
}

const pages: { name: string; role?: Role; path: () => string; ready: (page: Page) => Promise<void> }[] = [
  {
    name: 'catalogue',
    path: () => '/',
    ready: (page) => expect(page.getByRole('article').first()).toBeVisible(),
  },
  {
    name: 'event page',
    path: () => `/evenements/${run().event.id}`,
    ready: (page) => expect(page.getByRole('heading', { name: run().event.title })).toBeVisible(),
  },
  {
    name: 'my registrations',
    role: 'attendee',
    path: () => '/mes-inscriptions',
    ready: (page) => expect(page.getByRole('heading', { name: 'Mes inscriptions' })).toBeVisible(),
  },
  {
    name: 'my account',
    role: 'attendee',
    path: () => '/profil',
    ready: (page) => expect(page.getByLabel('Nom complet')).toBeVisible(),
  },
  {
    name: "organizer's events",
    role: 'organizer',
    path: () => '/organisation',
    ready: (page) => expect(page.getByRole('row').nth(1)).toBeVisible(),
  },
  {
    name: 'event form',
    role: 'organizer',
    path: () => '/organisation/nouveau',
    ready: (page) => expect(page.getByLabel('Titre')).toBeVisible(),
  },
  {
    name: 'administration of events',
    role: 'admin',
    path: () => '/administration/evenements',
    ready: (page) => expect(page.getByRole('row').nth(1)).toBeVisible(),
  },
  {
    name: 'administration of users',
    role: 'admin',
    path: () => '/administration/utilisateurs',
    ready: (page) => expect(page.getByRole('row').nth(1)).toBeVisible(),
  },
];

test.describe('accessibility', () => {
  for (const target of pages) {
    test(`${target.name} has no serious violation`, async ({ browser }) => {
      const context = await browser.newContext(
        target.role ? { storageState: storageStateOf(target.role) } : {},
      );
      const page = await context.newPage();
      await page.goto(target.path());
      await target.ready(page);
      await expectAccessible(page);
      await context.close();
    });
  }

  test('login page (Keycloak theme) has no serious violation', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('banner').getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { name: 'Connexion' })).toBeVisible();
    await expectAccessible(page);
  });
});
