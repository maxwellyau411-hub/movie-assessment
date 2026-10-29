<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { deleteMovie, fetchMovie } from '$lib/api';
  import ConfirmDelete from '$lib/components/ConfirmDelete.svelte';
  import MovieCredits from '$lib/components/MovieCredits.svelte';
  import Poster from '$lib/components/Poster.svelte';
  import { describeError, isStale } from '$lib/errors';
  import { GraphQLRequestError } from '$lib/graphql';
  import { showToast } from '$lib/toast.svelte';
  import type { Movie } from '$lib/types';

  let movie = $state<Movie | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  let confirming = $state(false);
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);

  async function load(id: string) {
    error = null;
    try {
      movie = await fetchMovie(id);
    } catch (e) {
      error = describeError(e);
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    loading = true;
    load(page.params.id!);
  });

  async function remove() {
    if (!movie) return;
    deleting = true;
    deleteError = null;
    try {
      await deleteMovie(movie.id, movie.version);
      showToast(`“${movie.title}” was deleted.`);
      await goto('/');
    } catch (e) {
      if (e instanceof GraphQLRequestError && e.code === 'NOT_FOUND') {
        showToast('That movie had already been deleted.');
        await goto('/');
        return;
      }
      deleteError = isStale(e)
        ? 'Someone changed this movie since you opened it. Close this, check the latest version, then delete again.'
        : describeError(e);
      if (isStale(e)) await load(movie.id);
    } finally {
      deleting = false;
    }
  }

  const runtime = (minutes: number) =>
    minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
</script>

<svelte:head>
  <title>{movie?.title ?? 'Movie'} · Movie Catalogue</title>
</svelte:head>

<p><a href="/">← All movies</a></p>

{#if loading}
  <div class="detail" aria-busy="true">
    <div class="picture skeleton" style="aspect-ratio: 2 / 3"></div>
    <div>
      <div class="skeleton" style="height: 2.5rem; width: 60%"></div>
      <div class="skeleton" style="height: 1rem; width: 35%; margin-top: 1rem"></div>
    </div>
  </div>
  <p class="visually-hidden" role="status">Loading movie…</p>
{:else if error}
  <div class="notice error" role="alert">
    <p>The movie could not be loaded. {error}</p>
    <button type="button" onclick={() => load(page.params.id!)}>Try again</button>
  </div>
{:else if !movie}
  <div class="notice" role="status">
    <p>This movie does not exist. It may have been deleted.</p>
    <a class="button" href="/">Back to movies</a>
  </div>
{:else}
  <div class="detail">
    <div class="picture"><Poster artwork={movie.artwork} title={movie.title} size="full" /></div>
    <div>
      <article>
        <h1>{movie.title}</h1>
        <p class="meta">
          {movie.releaseDate
            ? new Date(`${movie.releaseDate}T00:00:00Z`).toLocaleDateString('en-GB', {
                dateStyle: 'long',
                timeZone: 'UTC',
              })
            : 'Release date unknown'}
          {#if movie.runtimeMinutes}· {runtime(movie.runtimeMinutes)}{/if}
          <br />{movie.genres.map((g) => g.name).join(', ')}
        </p>
        {#if movie.synopsis}
          <p class="synopsis">{movie.synopsis}</p>
        {:else}
          <p class="muted">No synopsis.</p>
        {/if}
        <div class="actions">
          <a class="button primary" href="/movies/{movie.id}/edit">Edit movie and poster</a>
          <button
            type="button"
            onclick={() => {
              deleteError = null;
              confirming = true;
            }}>Delete</button
          >
        </div>
      </article>

      <MovieCredits {movie} onchanged={() => load(movie!.id)} />
    </div>
  </div>

  <ConfirmDelete
    open={confirming}
    title="Delete this movie?"
    {deleting}
    error={deleteError}
    onconfirm={remove}
    oncancel={() => (confirming = false)}
  >
    <p>
      “{movie.title}” will be deleted with its poster and its cast and creator credits. The people themselves
      are kept. This cannot be undone.
    </p>
  </ConfirmDelete>
{/if}

<style>
  .meta {
    line-height: 1.7;
  }
  .synopsis {
    white-space: pre-line;
    max-width: 42rem;
  }
</style>
