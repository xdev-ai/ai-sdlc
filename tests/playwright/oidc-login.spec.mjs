import { expect, test } from '@playwright/test';

const required = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be supplied by the disposable sandbox runner.`);
  return value;
};

const environment = () => {
  const portalBaseUrl = required('AISDLC_PORTAL_BASE_URL').replace(/\/$/, '');
  const keycloakBaseUrl = required('AISDLC_KEYCLOAK_BASE_URL').replace(/\/$/, '');
  return {
    portalBaseUrl,
    username: process.env.AISDLC_LOCAL_ADMIN_USERNAME || 'platform-admin',
    password: required('LOCAL_ADMIN_PASSWORD'),
    portalOrigin: new URL(portalBaseUrl).origin,
    keycloakOrigin: new URL(keycloakBaseUrl).origin
  };
};

const isKeycloakLogin = (keycloakOrigin) => (url) => {
  const parsed = new URL(url);
  return parsed.origin === keycloakOrigin && parsed.pathname.includes('/realms/ai-sdlc/protocol/openid-connect/auth');
};

test('signs into Keycloak and returns to an authenticated SSR workspace', async ({ page }) => {
  const { portalBaseUrl, username, password, portalOrigin, keycloakOrigin } = environment();
  await page.goto(`${portalBaseUrl}/app`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('status')).toContainText('Keycloak session needs renewal');
  await Promise.all([
    page.waitForURL(isKeycloakLogin(keycloakOrigin)),
    page.getByRole('link', { name: /sign in again/i }).click()
  ]);
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.locator('#password')).toBeVisible();

  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await Promise.all([
    page.waitForURL((url) => url.origin === portalOrigin && url.pathname.startsWith('/app')),
    page.locator('#kc-login').click()
  ]);

  await expect(page.locator('.connection-state.connected')).toContainText('Keycloak session connected');
  await expect(page.locator('form[action="/logout"]')).toBeVisible();
  await expect(page.locator('.app-shell')).toBeVisible();
});

test('offers a safe reauthentication path from the session-recovery page', async ({ page }) => {
  const { portalBaseUrl, keycloakOrigin } = environment();
  await page.goto(`${portalBaseUrl}/session-expired`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('status')).toContainText('Keycloak session needs renewal');
  await expect(page.locator('body')).toContainText('No draft, evidence, API token, or identity-provider detail is displayed');

  await Promise.all([
    page.waitForURL(isKeycloakLogin(keycloakOrigin)),
    page.getByRole('link', { name: /sign in again/i }).click()
  ]);
  await expect(page.locator('#username')).toBeVisible();
});
