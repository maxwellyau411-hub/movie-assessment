import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    // Same-origin in development too: session/CSRF cookies (Step 05) are SameSite=Strict.
    proxy: {
      '/graphql': process.env.CATALOGUE_URL ?? 'http://127.0.0.1:8080',
      // Images come straight from media-service (its HTTP port when run locally).
      '/media': process.env.MEDIA_URL ?? 'http://127.0.0.1:8081',
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
  },
});
