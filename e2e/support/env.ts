import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** Values of the repository's .env (Keycloak admin account), overridden by the environment. */
function dotenv(): Record<string, string> {
  try {
    const lines = readFileSync(join(__dirname, '..', '..', '.env'), 'utf8').split('\n');
    return Object.fromEntries(
      lines
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#') && line.includes('='))
        .map((line) => {
          const i = line.indexOf('=');
          return [line.slice(0, i), line.slice(i + 1).replace(/^["']|["']$/g, '')];
        }),
    );
  } catch {
    return {};
  }
}

const file = dotenv();
const value = (key: string, fallback?: string): string => {
  const found = process.env[key] ?? file[key] ?? fallback;
  if (found === undefined) {
    throw new Error(`${key} is required (environment or .env)`);
  }
  return found;
};

export const env = {
  keycloakUrl: process.env.E2E_KEYCLOAK_URL ?? `http://localhost:${value('KEYCLOAK_PORT', '8180')}`,
  gatewayUrl: process.env.E2E_GATEWAY_URL ?? 'http://localhost:8080',
  adminUsername: () => value('KEYCLOAK_ADMIN_USERNAME'),
  adminPassword: () => value('KEYCLOAK_ADMIN_PASSWORD'),
};
