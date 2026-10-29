<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { fetchMovie } from '$lib/api';
  import MovieForm from '$lib/components/MovieForm.svelte';
  import PosterUpload from '$lib/components/PosterUpload.svelte';
  import { describeError } from '$lib/errors';
  import { showToast } from '$lib/toast.svelte';
  import type { Movie } from '$lib/types';

  let movie = $state<Movie | null>(null);
  // A poster upload raises the movie's version, so the form's expected version follows it.
  let version = $state<number | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  async function load(id: string) {
    loading = true;
    error = null;
    try {
      movie = await fetchMovie(id);
      version = movie?.version ?? null;
    } catch (e) {
      error = describeError(e);
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    load(page.params.id!);
  });

  /** After a late cancel the poster may or may not have changed: show what the server has. */
  async function refreshPoster() {
    if (!movie) return;
    const latest = await fetchMovie(movie.id).catch(() => null);
    if (latest) {
      movie.artwork = latest.artwork;
      version = latest.version;
    }
  }
</script>

<svelte:head>
  <title>Edit {movie?.title ?? 'movie'} · Movie Catalogue</title>
</svelte:head>

<p><a href="/movies/{page.params.id}">← Back to the movie</a></p>

{#if loading}
  <div class="skeleton" style="height: 20rem" aria-busy="true"></div>
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
  <h1>Edit “{movie.title}”</h1>
  <PosterUpload
    movieId={movie.id}
    artwork={movie.artwork}
    title={movie.title}
    onuploaded={(result) => {
      movie!.artwork = result.artwork;
      version = result.version;
    }}
    oncancelled={refreshPoster}
  />
  <h2>Details</h2>
  <MovieForm
    {movie}
    {version}
    cancelHref="/movies/{movie.id}"
    onsaved={(saved) => {
      showToast('Changes saved.');
      goto(`/movies/${saved.id}`);
    }}
  />
{/if}
