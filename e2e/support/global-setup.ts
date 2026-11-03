import { randomBytes } from 'node:crypto';
import { api } from './api';
import { createTestClient, createUser, userToken } from './keycloak';
import { Identity, RunState, saveRun } from './run';

/**
 * Test identities in Keycloak (one per role, plus the address the sign-up test will use) and a published event,
 * unique to this run. Deleted by global teardown.
 */
export default async function globalSetup(): Promise<void> {
  const id = Date.now().toString(36);
  const password = () => randomBytes(12).toString('hex');
  const identity = (role: string, fullName: string): Identity => ({
    email: `e2e.pw.${role}.${id}@example.com`,
    password: password(),
    fullName,
  });
  const identities = {
    attendee: identity('attendee', `Participant ${id}`),
    organizer: identity('organizer', `Organisatrice ${id}`),
    admin: identity('admin', `Admin ${id}`),
  };

  await createTestClient();
  await createUser(identities.attendee.email, identities.attendee.password);
  await createUser(identities.organizer.email, identities.organizer.password, 'ORGANIZER');
  await createUser(identities.admin.email, identities.admin.password, 'ADMIN');

  // An event open to registration, created through the API: the visitor and accessibility tests start from it.
  const organizer = await userToken(identities.organizer.email, identities.organizer.password);
  const start = new Date(Date.now() + 45 * 86_400_000);
  start.setUTCHours(17, 0, 0, 0);
  const title = `Meetup Playwright ${id}`;
  const created = await api(organizer, 'POST', '/api/events', {
    title,
    description: 'Événement créé par les tests de bout en bout.',
    location: 'Paris, Station F',
    startAt: start.toISOString(),
    capacity: 3,
  });
  if (!created.ok) {
    throw new Error(`Event not created (${created.status}): ${await created.text()}`);
  }
  const event = (await created.json()) as { id: string };
  const published = await api(organizer, 'POST', `/api/events/${event.id}/publish`);
  if (!published.ok) {
    throw new Error(`Event not published (${published.status})`);
  }

  const state: RunState = {
    id,
    identities,
    signup: identity('signup', 'Nouvelle Inscrite'),
    event: { id: event.id, title },
  };
  saveRun(state);
}
