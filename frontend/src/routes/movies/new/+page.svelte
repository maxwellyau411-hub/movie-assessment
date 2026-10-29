<script lang="ts">
  import { goto } from '$app/navigation';
  import MovieForm from '$lib/components/MovieForm.svelte';
  import PosterUpload from '$lib/components/PosterUpload.svelte';
  import { showToast } from '$lib/toast.svelte';
  import type { Movie } from '$lib/types';

  let poster: PosterUpload;
  // Set once the movie exists; the upload then has a target (and Retry works if it failed).
  let created = $state<Movie | null>(null);
  let uploadFailed = $state(false);

  async function saved(movie: Movie) {
    created = movie;
    if (poster.hasFile() && !(await poster.start(movie.id))) {
      uploadFailed = true; // stay: the upload shows its error and a Retry button
      return;
    }
    showToast(`“${movie.title}” was created.`);
    await goto(`/movies/${movie.id}`);
  }
</script>

<svelte:head>
  <title>Add movie · Movie Catalogue</title>
</svelte:head>

<p><a href="/">← All movies</a></p>
<h1>Add a movie</h1>

{#if created && !uploadFailed}
  <p class="notice" role="status">“{created.title}” was created. Uploading its poster…</p>
{:else if created}
  <div class="notice" role="status">
    <p>
      “{created.title}” was created, but its poster was not uploaded. Retry below, or continue without one.
    </p>
    <a class="button" href="/movies/{created.id}">Go to the movie</a>
  </div>
{:else}
  <MovieForm movie={null} version={null} onsaved={saved} cancelHref="/" />
{/if}

<PosterUpload
  bind:this={poster}
  movieId={created?.id ?? null}
  artwork={null}
  title={created?.title ?? 'New movie'}
  onuploaded={() => {
    showToast(`“${created!.title}” was created.`);
    goto(`/movies/${created!.id}`);
  }}
/>
