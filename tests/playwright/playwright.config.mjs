import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'oidc-login.spec.mjs',
  timeout: 45_000,
  fullyParallel: false,
  forbidOnly: true,
  reporter: [['list']],
  use: {
    browserName: 'chromium',
    headless: true,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    ignoreHTTPSErrors: false
  }
});
