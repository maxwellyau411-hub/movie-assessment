import { expect, test } from '@playwright/test';

test('the status page reaches media-service through Catalogue over gRPC', async ({ page }) => {
  const isStatus = (response: { url(): string; request(): { postData(): string | null } }) =>
    response.url().endsWith('/graphql') && (response.request().postData() ?? '').includes('serviceStatus');

  // Listen before opening the page: a fast answer could otherwise arrive before the wait starts.
  const response = page.waitForResponse(isStatus);
  await page.goto('/status');
  const first = await response;
  expect(first.status()).toBe(200);
  expect(Object.keys((await first.json()).data.serviceStatus)).toEqual(['catalogue', 'mediaService']);

  // Right after the stack starts, Catalogue's first gRPC call can miss the 2 s health deadline and
  // honestly report DOWN; Refresh asks again, as a user would.
  await expect(async () => {
    if ((await page.getByTestId('media-service-status').textContent()) !== 'UP') {
      await page.getByRole('button', { name: 'Refresh' }).click();
    }
    await expect(page.getByTestId('media-service-status')).toHaveText('UP', { timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
  await expect(page.getByTestId('catalogue-status')).toHaveText('UP');
});

test('unknown client routes fall back to the SPA instead of a server 404', async ({ page }) => {
  const response = await page.goto('/movies/does-not-exist-yet');

  expect(response?.status()).toBe(200);
  await expect(page.getByText('This movie does not exist')).toBeVisible();
});

test('fonts are bundled with the app: nothing is loaded from another host', async ({ page, baseURL }) => {
  const origins = new Set<string>();
  page.on('request', (request) => origins.add(new URL(request.url()).origin));
  await page.goto('/');
  await expect(page.getByTestId('movie-list')).toBeVisible();

  await page.evaluate(() => document.fonts.ready);
  const loaded = await page.evaluate(() =>
    [...document.fonts].filter((f) => f.status === 'loaded').map((f) => f.family.replaceAll('"', '')),
  );
  expect(new Set(loaded)).toEqual(new Set(['Fraunces', 'IBM Plex Sans', 'IBM Plex Mono']));
  expect([...origins]).toEqual([new URL(baseURL!).origin]);
});
