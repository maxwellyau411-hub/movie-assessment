import { expect, test } from '@playwright/test';
import {
  createMovie,
  deleteMovieQuietly,
  demoPosterBytes,
  closeFilters,
  expectNoHorizontalScroll,
  gql,
  openFilters,
  uniquePrefix,
} from './helpers';

// Journeys against the running stack with the demo catalogue. Each test makes its own uniquely
// named movies and deletes them again, so desktop and mobile can run side by side.

test('browse: the home page lists movies with thumbnails, and a movie without a poster shows its initials', async ({
  page,
  request,
}) => {
  const title = `${uniquePrefix(test.info(), 'Quiet Harbour')}`;
  const id = await createMovie(request, title);
  try {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Movies', level: 1 })).toBeVisible();
    const thumbnail = page.getByTestId('movie-list').locator('img').first();
    await expect(thumbnail).toHaveAttribute('src', /^\/media\/thumbnails\/.+\.jpg$/);
    await expect(thumbnail).toHaveAttribute('loading', 'lazy');
    await expectNoHorizontalScroll(page);

    await page.getByLabel('Search titles').fill(title);
    const placeholder = page.getByTestId('movie-list').getByRole('img', { name: `No poster for ${title}` });
    await expect(placeholder).toHaveText('QH');

    // Its soft colour comes from a hash of the title, so the detail page shows the same one.
    const colour = await placeholder.evaluate((el) => getComputedStyle(el).backgroundColor);
    await page.getByRole('link', { name: new RegExp(title) }).click();
    await expect(page.getByRole('img', { name: `No poster for ${title}` })).toHaveCSS(
      'background-color',
      colour,
    );
  } finally {
    await deleteMovieQuietly(request, id);
  }
});

test('search, filters and sort live in the URL; Load more pages on; back restores the list and scroll position', async ({
  page,
  request,
}) => {
  const prefix = uniquePrefix(test.info(), 'Paging');
  const ids: string[] = [];
  for (let i = 1; i <= 25; i++)
    ids.push(await createMovie(request, `${prefix} ${String(i).padStart(2, '0')}`, ['WAR']));
  try {
    await page.goto('/');
    await page.getByLabel('Search titles').fill(prefix);
    await expect(page).toHaveURL(/\?q=Paging\+/);
    const cards = page.getByTestId('movie-list').getByRole('listitem');
    await expect(cards).toHaveCount(20);
    await expect(page.getByText('Showing 20 of 25 movies')).toBeVisible();

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(cards).toHaveCount(25);
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0);

    // Sort and genre are in the URL too, and survive a refresh.
    await openFilters(page);
    await page.getByLabel('Sort by').selectOption('TITLE_DESC');
    await expect(page).toHaveURL(/sort=TITLE_DESC/);
    await expect(cards.first()).toContainText(`${prefix} 25`);
    await page.getByLabel('War').check();
    await expect(page).toHaveURL(/genre=WAR/);
    await closeFilters(page);
    await page.reload();
    await expect(page.getByLabel('Search titles')).toHaveValue(prefix);
    await openFilters(page);
    await expect(page.getByLabel('Sort by')).toHaveValue('TITLE_DESC');
    await expect(page.getByLabel('War')).toBeChecked();
    await closeFilters(page);
    await expect(cards.first()).toContainText(`${prefix} 25`);

    // Load everything, scroll to the end, open the last movie, come back.
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(cards).toHaveCount(25);
    const last = page.getByRole('link', { name: new RegExp(`${prefix} 01`) });
    await last.scrollIntoViewIfNeeded();
    const scrollBefore = await page.evaluate(() => window.scrollY);
    expect(scrollBefore).toBeGreaterThan(0);
    await last.click();
    await expect(page.getByRole('heading', { name: `${prefix} 01`, level: 1 })).toBeVisible();

    await page.goBack();
    await expect(cards).toHaveCount(25);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(scrollBefore - 50);
    expect(await page.evaluate(() => window.scrollY)).toBeLessThan(scrollBefore + 50);
  } finally {
    for (const id of ids) await deleteMovieQuietly(request, id);
  }
});

test('create (with the duplicate warning), edit with field errors, upload a poster, and delete', async ({
  page,
  request,
}) => {
  const title = uniquePrefix(test.info(), 'Created');
  const created: string[] = [];
  try {
    // Create
    await page.goto('/');
    await page.getByRole('link', { name: 'Add movie' }).click();
    await page.getByRole('button', { name: 'Create movie' }).click();
    await expect(page.getByText('Enter a title.')).toBeVisible();
    await expect(page.getByText('Choose at least one genre.')).toBeVisible();
    await page.getByLabel('Title').fill(title);
    await page.getByLabel('Drama').check();
    await page.getByRole('button', { name: 'Create movie' }).click();
    await expect(page.getByRole('heading', { name: title, level: 1 })).toBeVisible();
    // The message appears at the bottom of the screen.
    const message = page.getByText(`“${title}” was created.`);
    await expect(message).toBeVisible();
    const box = (await message.boundingBox())!;
    expect(box.y + box.height).toBeGreaterThan(page.viewportSize()!.height - 120);
    created.push(page.url().split('/').pop()!);

    // The same title again: warned, then saved anyway.
    await page.goto('/movies/new');
    await page.getByLabel('Title').fill(title);
    await page.getByLabel('Comedy').check();
    await page.getByRole('button', { name: 'Create movie' }).click();
    const dialog = page.getByRole('dialog', { name: 'Possible duplicate' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('link', { name: new RegExp(title) })).toBeVisible();
    await dialog.getByRole('button', { name: 'Save anyway' }).click();
    await expect(page.getByRole('heading', { name: title, level: 1 })).toBeVisible();
    const id = page.url().split('/').pop()!;
    created.push(id);

    // Edit: a server-side field error appears next to its field.
    await page.getByRole('link', { name: 'Edit movie and poster' }).click();
    await page.getByLabel('Runtime in minutes').fill('2000');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.locator('#runtime-error')).toHaveText('Must be between 1 and 1440.');
    await expect(page.getByLabel('Runtime in minutes')).toHaveAttribute('aria-invalid', 'true');

    // Upload: a file that is not really an image is refused with the server's reason.
    await page.getByLabel('Choose an image').setInputFiles({
      name: 'fake.png',
      mimeType: 'image/png',
      buffer: Buffer.from('this is not a picture'),
    });
    await page.getByRole('button', { name: 'Upload poster' }).click();
    await expect(page.getByText('Unsupported image format; use JPEG, PNG or WebP.')).toBeVisible();

    // A real image: preview, progress, then saved.
    await page.getByLabel('Choose an image').setInputFiles({
      name: 'poster.png',
      mimeType: 'image/png',
      buffer: await demoPosterBytes(request),
    });
    await expect(page.getByAltText('Preview of the chosen file')).toBeVisible();
    await page.getByRole('button', { name: 'Upload poster' }).click();
    await expect(page.getByText('Poster saved.')).toBeVisible();

    // The upload raised the version; saving the form afterwards still works.
    // The other movie with this title triggers the duplicate warning on edit too.
    await page.getByLabel('Runtime in minutes').fill('95');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await page
      .getByRole('dialog', { name: 'Possible duplicate' })
      .getByRole('button', { name: 'Save anyway' })
      .click();
    await expect(page.getByText('Changes saved.')).toBeVisible();
    await expect(page.getByText('1 h 35 min')).toBeVisible();
    await expect(page.getByRole('img', { name: `Poster: ${title}` })).toHaveAttribute(
      'src',
      /^\/media\/artwork\//,
    );

    // Delete
    await page.getByRole('button', { name: 'Delete' }).click();
    const confirm = page.getByRole('dialog', { name: 'Delete this movie?' });
    await expect(confirm).toContainText('The people themselves are kept');
    await confirm.getByRole('button', { name: 'Delete' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByText(`“${title}” was deleted.`)).toBeVisible();
    const gone = await gql(request, `{ movie(id: "${id}") { id } }`);
    expect(gone.movie).toBeNull();
  } finally {
    for (const id of created) await deleteMovieQuietly(request, id);
  }
});

test('link and unlink cast and creator credits on the movie screen', async ({ page, request }) => {
  const title = uniquePrefix(test.info(), 'Credits');
  const id = await createMovie(request, title);
  // Its own person: credit changes raise the person's version, so two runs sharing one would collide.
  const name = uniquePrefix(test.info(), 'Credited Person');
  const person = await gql(
    request,
    `mutation($input: PersonInput!) { createPerson(input: $input, confirmDuplicate: true) { ... on PersonSaved { person { id version } } } }`,
    { input: { name, categories: ['CAST', 'CREATOR'] } },
  );
  const personId = person.createPerson.person.id;
  try {
    await page.goto(`/movies/${id}`);
    await expect(page.getByText('No cast yet.')).toBeVisible();

    await page.getByLabel('Add a cast member').fill(name);
    await page.getByRole('button', { name: new RegExp(`^${name}`) }).click();
    await page.getByLabel('Character').fill('The Keeper');
    await page.getByRole('button', { name: 'Add to cast' }).click();
    await expect(page.getByText(`${name} added to the cast.`)).toBeVisible();
    await expect(page.getByRole('region', { name: 'Cast' }).getByText('as The Keeper')).toBeVisible();

    await page.getByLabel('Add a creator').fill(name);
    await page.getByRole('button', { name: new RegExp(`^${name}`) }).click();
    await page.getByLabel('Role').selectOption('DIRECTOR');
    await page.getByRole('button', { name: 'Add creator' }).click();
    await expect(page.getByRole('region', { name: 'Creators' }).getByText('· Director')).toBeVisible();

    // The same credit twice is refused with the server's message.
    await page.getByLabel('Add a cast member').fill(name);
    await page.getByRole('button', { name: new RegExp(`^${name}`) }).click();
    await page.getByRole('button', { name: 'Add to cast' }).click();
    await expect(page.getByText(`${name} is already in this movie's cast`)).toBeVisible();

    await page.getByRole('button', { name: `Remove ${name} from the cast` }).click();
    await expect(page.getByText('No cast yet.')).toBeVisible();
    await page.getByRole('button', { name: `Remove ${name} as Director` }).click();
    await expect(page.getByText('No creators yet.')).toBeVisible();
  } finally {
    await deleteMovieQuietly(request, id);
    const latest = await gql(request, `{ person(id: "${personId}") { version } }`);
    await gql(
      request,
      `mutation { deletePerson(id: "${personId}", expectedVersion: ${latest.person.version}) }`,
    );
  }
});

test('a stale edit tells the user to reload', async ({ page, request }) => {
  const title = uniquePrefix(test.info(), 'Stale');
  const id = await createMovie(request, title);
  try {
    await page.goto(`/movies/${id}/edit`);
    await expect(page.getByLabel('Title')).toHaveValue(title);
    // Someone else saves first.
    await gql(
      request,
      `mutation($id: ID!) { updateMovie(id: $id, expectedVersion: 0, input: { title: "${title}", genreCodes: ["DRAMA"], synopsis: "Changed elsewhere" }, confirmDuplicate: true) { __typename } }`,
      { id },
    );

    await page.getByLabel('Synopsis').fill('My change');
    await page.getByRole('button', { name: 'Save changes' }).click();

    await expect(page.getByRole('alert')).toContainText('Someone else changed this since you opened it');
    await page.getByRole('button', { name: /Reload/ }).click();
    await expect(page.getByLabel('Synopsis')).toHaveValue('Changed elsewhere');
  } finally {
    await deleteMovieQuietly(request, id);
  }
});

test('no screen scrolls sideways', async ({ page, request }) => {
  const data = await gql(
    request,
    '{ movies(first: 1) { nodes { id } } castMembers(first: 1) { nodes { id } } }',
  );
  for (const path of [
    '/',
    '/cast',
    '/creators',
    '/movies/new',
    '/people/new',
    `/movies/${data.movies.nodes[0].id}`,
    `/movies/${data.movies.nodes[0].id}/edit`,
    `/people/${data.castMembers.nodes[0].id}`,
  ]) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    await expectNoHorizontalScroll(page);
  }
});

test('film archive layout: 2:3 posters, columns for the screen, sticky search, filters in a sidebar or a sheet', async ({
  page,
}) => {
  const phone = page.viewportSize()!.width < 1024;
  await page.goto('/');
  const cards = page.getByTestId('movie-list').getByRole('listitem');
  await expect(cards.nth(7)).toBeVisible();

  const poster = (await cards.first().locator('.poster').boundingBox())!;
  expect(poster.height / poster.width).toBeCloseTo(1.5, 1);
  // 2 per row on a 390 px phone; 5 at 1280 px (6 from 1344 px).
  const tops = await cards.evaluateAll((items) =>
    items.slice(0, 8).map((item) => Math.round(item.getBoundingClientRect().top)),
  );
  expect(tops.filter((top) => top === tops[0])).toHaveLength(phone ? 2 : 5);

  if (phone) {
    await expect(page.getByRole('complementary', { name: 'Filters' })).toHaveCount(0);
    await openFilters(page);
    // A bottom sheet: its lower edge sits on the bottom of the screen (after the slide-up).
    const sheet = page.getByRole('dialog', { name: 'Filters' });
    await expect
      .poll(async () => {
        const box = (await sheet.boundingBox())!;
        return Math.round(box.y + box.height);
      })
      .toBe(page.viewportSize()!.height);
    await closeFilters(page);
  } else {
    await expect(page.getByRole('complementary', { name: 'Filters' })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Filters/ })).toHaveCount(0);
  }

  // The search bar stays at the top while the list scrolls.
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(100);
  await expect.poll(async () => (await page.getByRole('search').boundingBox())!.y).toBeLessThanOrEqual(1);
});
