import { env } from './env';

/** Test-only public client with the password grant, created and deleted by each run (never in the realm file). */
export const TEST_CLIENT = 'e2e-playwright';
const REALM = 'event-platform';

async function token(realm: string, clientId: string, username: string, password: string): Promise<string> {
  const response = await fetch(`${env.keycloakUrl}/realms/${realm}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: clientId, username, password }),
  });
  if (!response.ok) {
    throw new Error(`No token for ${username} (${response.status})`);
  }
  return ((await response.json()) as { access_token: string }).access_token;
}

/** Keycloak admin REST call on the realm (admin tokens are short-lived: one per call). */
async function admin(method: string, path: string, body?: unknown): Promise<Response> {
  const bearer = await token('master', 'admin-cli', env.adminUsername(), env.adminPassword());
  return fetch(`${env.keycloakUrl}/admin/realms/${REALM}${path}`, {
    method,
    headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function idOf(path: string): Promise<string | undefined> {
  const found = (await (await admin('GET', path)).json()) as { id: string }[];
  return found[0]?.id;
}

export async function createTestClient(): Promise<void> {
  await deleteTestClient();
  const response = await admin('POST', '/clients', {
    clientId: TEST_CLIENT,
    publicClient: true,
    standardFlowEnabled: false,
    directAccessGrantsEnabled: true,
    protocolMappers: [
      {
        name: 'event-platform-api audience',
        protocol: 'openid-connect',
        protocolMapper: 'oidc-audience-mapper',
        config: {
          'included.custom.audience': 'event-platform-api',
          'access.token.claim': 'true',
          'id.token.claim': 'false',
        },
      },
    ],
  });
  if (!response.ok) {
    throw new Error(`Test client not created (${response.status})`);
  }
}

export async function deleteTestClient(): Promise<void> {
  const id = await idOf(`/clients?clientId=${TEST_CLIENT}`);
  if (id) {
    await admin('DELETE', `/clients/${id}`);
  }
}

/** A user with the default ATTENDEE role, plus a realm role if given. */
export async function createUser(email: string, password: string, role?: string): Promise<void> {
  const response = await admin('POST', '/users', {
    username: email,
    email,
    emailVerified: true,
    enabled: true,
    firstName: 'E2E',
    lastName: 'Playwright',
    credentials: [{ type: 'password', value: password, temporary: false }],
  });
  if (!response.ok) {
    throw new Error(`User ${email} not created (${response.status})`);
  }
  if (role) {
    const id = await idOf(`/users?email=${encodeURIComponent(email)}&exact=true`);
    const roleRepresentation = await (await admin('GET', `/roles/${role}`)).json();
    await admin('POST', `/users/${id}/role-mappings/realm`, [roleRepresentation]);
  }
}

export async function deleteUser(email: string): Promise<void> {
  const id = await idOf(`/users?email=${encodeURIComponent(email)}&exact=true`);
  if (id) {
    await admin('DELETE', `/users/${id}`);
  }
}

/** Access token of a test user, for API calls through the gateway (setup and cleanup only). */
export function userToken(email: string, password: string): Promise<string> {
  return token(REALM, TEST_CLIENT, email, password);
}
