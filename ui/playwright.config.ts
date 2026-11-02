import { defineConfig } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';

const envFile = 'e2e/.stack/stack.env';
const stack = existsSync(envFile)
  ? Object.fromEntries(readFileSync(envFile, 'utf8').trim().split('\n').map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]))
  : {};

export default defineConfig({
  testDir: 'e2e',
  outputDir: 'e2e/test-results',
  // One seeded database, and the tests are a story that changes it in order.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    baseURL: stack['UI_URL'],
    browserName: 'chromium',
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 15_000,
    trace: 'retain-on-failure',
  },
});
