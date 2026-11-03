import { defineConfig } from 'vitest/config';

// Loaded by the Angular unit-test builder (runnerConfig: true) on top of its own settings.
export default defineConfig({
  test: {
    // The first test of a file compiles its components (Angular Material included): over Vitest's 5 s on a busy
    // machine, such as a laptop building Docker images at the same time or a shared CI runner.
    testTimeout: 15_000,
  },
});
