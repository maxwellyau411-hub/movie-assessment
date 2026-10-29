import type { APIRequestContext, Page, TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';

/** GraphQL straight to the API, for test set-up and clean-up. Fails the test on any error. */
export async function gql<T = Record<string, any>>(
  request: APIRequestContext,
  query: string,
  variables: Record<string, unknown> = {},
): Promise<T> {
  const response = await request.post('/graphql', { data: { query, variables } });
  const body = await response.json();
  expect(body.errors, JSON.stringify(body.errors)).toBeUndefined();
  return body.data as T;
}

/** A prefix no other test (or the other viewport running in parallel) uses. */
export function uniquePrefix(info: TestInfo, label: string): string {
  return `${label} ${info.project.name} ${Date.now().toString(36)}`;
}

export async function createMovie(
  request: APIRequestContext,
  title: string,
  genres = ['DRAMA'],
): Promise<string> {
  const data = await gql(
    request,
    `mutation($input: MovieInput!) { createMovie(input: $input, confirmDuplicate: true) { ... on MovieSaved { movie { id } } } }`,
    { input: { title, genreCodes: genres } },
  );
  return data.createMovie.movie.id;
}

export async function deleteMovieQuietly(request: APIRequestContext, id: string) {
  const data = await request.post('/graphql', { data: { query: `{ movie(id: "${id}") { version } }` } });
  const version = (await data.json()).data?.movie?.version;
  if (version == null) return;
  await request.post('/graphql', {
    data: { query: `mutation { deleteMovie(id: "${id}", expectedVersion: ${version}) }` },
  });
}

/** The page may not scroll sideways at this viewport (R5: no horizontal scroll). */
export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow, `${page.url()} scrolls sideways by ${overflow}px`).toBeLessThanOrEqual(0);
}

/** Bytes of a real demo poster, to upload in tests. */
export async function demoPosterBytes(request: APIRequestContext): Promise<Buffer> {
  const data = await gql(request, '{ movies(first: 20) { nodes { artwork { fullUrl } } } }');
  const url = data.movies.nodes.find((m: { artwork: unknown }) => m.artwork).artwork.fullUrl;
  return (await request.get(url)).body();
}

/** Below 64rem (1024 px) the movie filters live in a bottom sheet behind a "Filters" button. */
function filtersInSheet(page: Page): boolean {
  return (page.viewportSize()?.width ?? 0) < 1024;
}

export async function openFilters(page: Page) {
  if (!filtersInSheet(page)) return;
  await page.getByRole('button', { name: /^Filters/ }).click();
  await expect(page.getByRole('dialog', { name: 'Filters' })).toBeVisible();
}

export async function closeFilters(page: Page) {
  if (!filtersInSheet(page)) return;
  await page.getByRole('button', { name: 'Show movies' }).click();
  await expect(page.getByRole('dialog', { name: 'Filters' })).toBeHidden();
}
