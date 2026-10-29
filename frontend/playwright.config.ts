import { defineConfig } from '@playwright/test';

// Browser journeys run against a running stack (default: `docker compose up`), not a dev server.
export default defineConfig({
  testDir: 'e2e',
  timeout: 30_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8088',
    // Installed Google Chrome; CI can run `pnpm exec playwright install chrome`.
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1280, height: 800 } } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
