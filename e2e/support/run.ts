import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

export type Role = 'attendee' | 'organizer' | 'admin';

export interface Identity {
  email: string;
  password: string;
  /** Display name given on the welcome page, the first time the identity signs in. */
  fullName: string;
}

/** What global setup created for this run, read by the tests and by global teardown. */
export interface RunState {
  id: string;
  identities: Record<Role, Identity>;
  /** Created through the sign-up page by a test, deleted at the end like the others. */
  signup: Identity;
  /** A published event created through the API for the visitor and accessibility tests. */
  event: { id: string; title: string };
}

export const AUTH_DIR = join(__dirname, '..', '.auth');
const STATE_FILE = join(AUTH_DIR, 'run.json');

export function storageStateOf(role: Role): string {
  return join(AUTH_DIR, `${role}.json`);
}

export function saveRun(state: RunState): void {
  mkdirSync(AUTH_DIR, { recursive: true });
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

export function run(): RunState {
  return JSON.parse(readFileSync(STATE_FILE, 'utf8')) as RunState;
}
