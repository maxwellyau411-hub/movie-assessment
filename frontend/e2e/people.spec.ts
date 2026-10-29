import { expect, test } from '@playwright/test';
import { createMovie, deleteMovieQuietly, gql, uniquePrefix } from './helpers';

test('create, find, edit and delete a person; a credited person cannot be deleted', async ({
  page,
  request,
}) => {
  const name = uniquePrefix(test.info(), 'Person');
  const movieTitle = uniquePrefix(test.info(), 'Blocking film');
  const movieId = await createMovie(request, movieTitle);
  try {
    // Create from the Cast list: Cast is pre-ticked.
    await page.goto('/cast');
    await page.getByRole('link', { name: 'Add person' }).click();
    await expect(page.getByLabel('Cast')).toBeChecked();
    await page.getByLabel('Name').fill(name);
    await page.getByRole('button', { name: 'Create person' }).click();
    await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
    await expect(page.getByText(`${name} was created.`)).toBeVisible();
    const id = page.url().split('/').pop()!;

    // Find in the Cast list by search (in the URL).
    await page.goto('/cast');
    await page.getByLabel('Search names').fill(name);
    await expect(page).toHaveURL(/\/cast\?q=/);
    await expect(page.getByTestId('people-list').getByRole('link', { name: new RegExp(name) })).toBeVisible();

    // Edit: add the Creator category.
    await page.goto(`/people/${id}/edit`);
    await page.getByLabel('Creator').check();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByText('Changes saved.')).toBeVisible();
    await expect(page.getByText('Cast and Creator')).toBeVisible();

    // Credited in a movie: delete is refused and names the movie.
    await gql(request, `mutation { addCastCredit(movieId: "${movieId}", personId: "${id}") { id } }`);
    await page.reload();
    await page.getByRole('button', { name: 'Delete' }).click();
    const dialog = page.getByRole('dialog', { name: 'Delete this person?' });
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect(dialog).toContainText(`${name} is credited in 1 movie`);
    await expect(dialog.getByRole('link', { name: movieTitle })).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    // Without credits it can be deleted.
    await deleteMovieQuietly(request, movieId);
    await page.reload();
    await page.getByRole('button', { name: 'Delete' }).click();
    await page
      .getByRole('dialog', { name: 'Delete this person?' })
      .getByRole('button', { name: 'Delete' })
      .click();
    await expect(page).toHaveURL(/\/cast$/);
    await expect(page.getByText(`${name} was deleted.`)).toBeVisible();
  } finally {
    await deleteMovieQuietly(request, movieId);
  }
});

test('the Creators list filters by role through the URL', async ({ page }) => {
  await page.goto('/creators');
  await page.getByLabel('Role').selectOption('DIRECTOR');
  await expect(page).toHaveURL(/\/creators\?role=DIRECTOR/);
  await expect(page.getByTestId('people-list').getByRole('link', { name: /Ilse Varnholt/ })).toBeVisible();

  await page.goto('/creators?role=COMPOSER&q=zzzz-nobody');
  await expect(page.getByLabel('Role')).toHaveValue('COMPOSER');
  await expect(page.getByText('Nobody matches these filters.')).toBeVisible();
});
