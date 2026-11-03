import { api } from './api';
import { deleteTestClient, deleteUser, userToken } from './keycloak';
import { run } from './run';

/**
 * Leaves nothing behind: the events of the run cancelled (they leave the catalogue), the profiles erased by their
 * owners (right to erasure), then the Keycloak users and the test client deleted. Best effort: a failed test must
 * not prevent the cleanup of the rest.
 */
export default async function globalTeardown(): Promise<void> {
  let state;
  try {
    state = run();
  } catch {
    return; // setup did not get far enough to create anything
  }
  const everyone = [...Object.values(state.identities), state.signup];

  await attempt(async () => {
    const organizer = await userToken(state.identities.organizer.email, state.identities.organizer.password);
    const mine = await api(organizer, 'GET', '/api/events?organizer=me&size=100');
    const events = ((await mine.json()) as { content: { id: string; status: string }[] }).content;
    for (const event of events.filter((e) => e.status !== 'CANCELLED')) {
      await api(organizer, 'POST', `/api/events/${event.id}/cancel`);
    }
  });
  for (const identity of everyone) {
    await attempt(async () => {
      const token = await userToken(identity.email, identity.password);
      await api(token, 'DELETE', '/api/users/me');
    });
    await attempt(() => deleteUser(identity.email));
  }
  await attempt(deleteTestClient);
}

async function attempt(step: () => Promise<unknown>): Promise<void> {
  try {
    await step();
  } catch {
    // Already gone, or never created.
  }
}
