import { expect, test } from '@playwright/test';

// Needs the demo catalogue (CATALOGUE_DEMO_DATA=true, the Compose default).
test('demo posters are served by media-service through the single web entry point', async ({ request }) => {
  const result = await request.post('/graphql', {
    data: {
      query: '{ movies(first: 50) { nodes { title artwork { thumbnailUrl fullUrl width height } } } }',
    },
  });
  // Other tests add movies without posters, so take the first one that has artwork.
  const movie = (await result.json()).data.movies.nodes.find((m: { artwork: unknown }) => m.artwork);
  expect(movie.artwork).toMatchObject({ width: 600, height: 900 });

  for (const [url, type] of [
    [movie.artwork.thumbnailUrl, 'image/jpeg'],
    [movie.artwork.fullUrl, 'image/png'],
  ]) {
    const image = await request.get(url);
    expect(image.status()).toBe(200);
    expect(image.headers()['content-type']).toBe(type);
    expect(image.headers()['cache-control']).toContain('immutable');
    expect(image.headers()['x-content-type-options']).toBe('nosniff');
  }
});
