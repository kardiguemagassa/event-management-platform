import { defineConfig, devices } from '@playwright/test';

/**
 * Browser tests of the whole platform, as a user sees it: the web application on http://localhost:4200 (nginx,
 * same origin as the gateway) and the Keycloak pages on http://localhost:8180. Start the stack first:
 * docker compose --profile app up -d --wait
 */
export default defineConfig({
  testDir: './tests',
  globalSetup: './support/global-setup.ts',
  globalTeardown: './support/global-teardown.ts',
  // The scenarios share test identities and one event: one worker keeps their order and the run readable.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // No retry: the journey is a chain of steps on shared data; a failure keeps its trace instead of being hidden.
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4200',
    locale: 'fr-FR',
    timezoneId: 'Europe/Paris',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    // Signs each role in through the Keycloak page once; the other projects reuse the sessions.
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['setup'],
      testIgnore: [/auth\.setup\.ts/, /mobile\.spec\.ts/],
    },
    {
      name: 'mobile',
      use: { ...devices['Pixel 7'] },
      dependencies: ['setup'],
      testMatch: /mobile\.spec\.ts/,
    },
  ],
});
