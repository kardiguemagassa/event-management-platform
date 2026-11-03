import { env } from './env';

/** Call of the API through the gateway, with a user's bearer token (setup and cleanup, never the tests). */
export async function api(token: string, method: string, path: string, body?: unknown): Promise<Response> {
  return fetch(`${env.gatewayUrl}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
