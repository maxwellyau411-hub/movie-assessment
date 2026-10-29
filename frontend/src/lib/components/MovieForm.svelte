<script lang="ts">
  import { saveMovie } from '$lib/api';
  import { describeError, isStale } from '$lib/errors';
  import { genres as loadGenres } from '$lib/genres';
  import { GraphQLRequestError } from '$lib/graphql';
  import { optional, sentence } from '$lib/text';
  import { showToast } from '$lib/toast.svelte';
  import type { Genre, Movie, MovieInput, MovieRef } from '$lib/types';
  import SaveDuplicates from './SaveDuplicates.svelte';

  /**
   * Create (movie = null) or edit. `version` is the version the user is editing; the edit page
   * passes it separately because a poster upload raises it without touching these fields.
   */
  let {
    movie,
    version,
    onsaved,
    cancelHref,
  }: { movie: Movie | null; version: number | null; onsaved: (movie: Movie) => void; cancelHref: string } =
    $props();

  // The form starts from the loaded movie and is then the user's own copy.
  // svelte-ignore state_referenced_locally
  const initial = movie;
  let title = $state(initial?.title ?? '');
  let synopsis = $state(initial?.synopsis ?? '');
  let releaseDate = $state(initial?.releaseDate ?? '');
  let runtime = $state(initial?.runtimeMinutes?.toString() ?? '');
  let genreCodes = $state<string[]>(initial?.genres.map((g) => g.code) ?? []);

  let genreList = $state<Genre[]>([]);
  let genreError = $state<string | null>(null);
  let saving = $state(false);
  let fieldErrors = $state<Record<string, string>>({});
  // A stale version stays on the form, with its Reload button; other errors are toasts (D59).
  let staleError = $state<string | null>(null);
  let duplicates = $state<{ message: string; candidates: MovieRef[] } | null>(null);

  loadGenres().then(
    (g) => (genreList = g),
    (e) => (genreError = describeError(e)),
  );

  function input(): MovieInput {
    return {
      title,
      synopsis: optional(synopsis),
      releaseDate: releaseDate || null,
      runtimeMinutes: runtime.trim() === '' ? null : Number(runtime),
      genreCodes,
    };
  }

  /** Quick checks for instant feedback; the server checks everything again. */
  function precheck(): Record<string, string> {
    const errors: Record<string, string> = {};
    if (title.trim() === '') errors.title = 'Enter a title.';
    if (genreCodes.length === 0) errors.genreCodes = 'Choose at least one genre.';
    if (runtime.trim() !== '' && !/^\d+$/.test(runtime.trim()))
      errors.runtimeMinutes = 'Enter whole minutes.';
    return errors;
  }

  async function save(confirmDuplicate = false) {
    fieldErrors = precheck();
    staleError = null;
    if (Object.keys(fieldErrors).length) {
      showToast('Please correct the highlighted fields.', 'error');
      return;
    }
    saving = true;
    try {
      const result = await saveMovie(movie?.id ?? null, version, input(), confirmDuplicate);
      if (result.kind === 'duplicates') {
        duplicates = { message: result.message, candidates: result.candidates };
        return;
      }
      duplicates = null;
      onsaved(result.value);
    } catch (e) {
      duplicates = null;
      if (e instanceof GraphQLRequestError) {
        fieldErrors = Object.fromEntries(
          Object.entries(e.fieldErrors).map(([field, m]) => [field, sentence(m)]),
        );
      }
      if (isStale(e)) staleError = describeError(e);
      else showToast(describeError(e), 'error');
    } finally {
      saving = false;
    }
  }

  const invalid = (field: string) => (fieldErrors[field] ? 'true' : undefined);
</script>

<form
  class="form-width"
  novalidate
  onsubmit={(e) => {
    e.preventDefault();
    save();
  }}
>
  {#if staleError}
    <div class="notice error" role="alert">
      <p>{staleError}</p>
      <button type="button" onclick={() => location.reload()}>Reload (your unsaved changes are lost)</button>
    </div>
  {/if}

  <div class="field">
    <label for="title">Title</label>
    <input
      id="title"
      type="text"
      maxlength="200"
      required
      bind:value={title}
      aria-invalid={invalid('title')}
      aria-describedby={fieldErrors.title ? 'title-error' : undefined}
    />
    {#if fieldErrors.title}<p class="field-error" id="title-error">{fieldErrors.title}</p>{/if}
  </div>

  <fieldset class="field" aria-describedby={fieldErrors.genreCodes ? 'genres-error' : undefined}>
    <legend>Genres</legend>
    {#if genreError}<p class="field-error">Genres could not be loaded: {genreError}</p>{/if}
    <div class="choices">
      {#each genreList as genre (genre.code)}
        <label class="choice">
          <input type="checkbox" value={genre.code} bind:group={genreCodes} />
          {genre.name}
        </label>
      {/each}
    </div>
    {#if fieldErrors.genreCodes}<p class="field-error" id="genres-error">{fieldErrors.genreCodes}</p>{/if}
  </fieldset>

  <div class="row">
    <div class="field">
      <label for="releaseDate">Release date <span class="muted">(optional)</span></label>
      <input
        id="releaseDate"
        type="date"
        bind:value={releaseDate}
        aria-invalid={invalid('releaseDate')}
        aria-describedby={fieldErrors.releaseDate ? 'release-error' : undefined}
      />
      {#if fieldErrors.releaseDate}<p class="field-error" id="release-error">
          {fieldErrors.releaseDate}
        </p>{/if}
    </div>
    <div class="field">
      <label for="runtime">Runtime in minutes <span class="muted">(optional)</span></label>
      <input
        id="runtime"
        type="text"
        inputmode="numeric"
        bind:value={runtime}
        aria-invalid={invalid('runtimeMinutes')}
        aria-describedby={fieldErrors.runtimeMinutes ? 'runtime-error' : undefined}
      />
      {#if fieldErrors.runtimeMinutes}<p class="field-error" id="runtime-error">
          {fieldErrors.runtimeMinutes}
        </p>{/if}
    </div>
  </div>

  <div class="field">
    <label for="synopsis">Synopsis <span class="muted">(optional)</span></label>
    <textarea
      id="synopsis"
      maxlength="5000"
      bind:value={synopsis}
      aria-invalid={invalid('synopsis')}
      aria-describedby={fieldErrors.synopsis ? 'synopsis-error' : undefined}></textarea>
    {#if fieldErrors.synopsis}<p class="field-error" id="synopsis-error">{fieldErrors.synopsis}</p>{/if}
  </div>

  <div class="actions">
    <button type="submit" class="primary" disabled={saving} aria-busy={saving}>
      {saving ? 'Saving…' : movie ? 'Save changes' : 'Create movie'}
    </button>
    <a class="button" href={cancelHref}>Cancel</a>
  </div>
</form>

<SaveDuplicates
  open={duplicates != null}
  message={duplicates?.message ?? ''}
  candidates={(duplicates?.candidates ?? []).map((m) => ({
    id: m.id,
    label: `${m.title} (${m.releaseYear ?? 'year unknown'})`,
    href: `/movies/${m.id}`,
  }))}
  {saving}
  onconfirm={() => save(true)}
  oncancel={() => (duplicates = null)}
/>

<style>
  .row {
    display: grid;
    gap: 0 1rem;
  }
  @media (min-width: 36rem) {
    .row {
      grid-template-columns: 1fr 1fr;
    }
  }
</style>
