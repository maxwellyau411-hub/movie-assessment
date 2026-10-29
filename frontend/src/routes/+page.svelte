<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { fetchMovies } from '$lib/api';
  import { debounce } from '$lib/async';
  import Dialog from '$lib/components/Dialog.svelte';
  import ListFooter from '$lib/components/ListFooter.svelte';
  import Poster from '$lib/components/Poster.svelte';
  import { genres as loadGenres } from '$lib/genres';
  import { movieListQuery, readMovieListState, type MovieListState, type MovieSort } from '$lib/listState';
  import { pagedList } from '$lib/pagedList.svelte';
  import type { Genre, MovieSummary } from '$lib/types';
  import { onDestroy } from 'svelte';
  import { MediaQuery } from 'svelte/reactivity';

  const SORT_LABELS: Record<MovieSort, string> = {
    TITLE_ASC: 'Title A–Z',
    TITLE_DESC: 'Title Z–A',
    RELEASE_DATE_NEWEST: 'Newest first',
    RELEASE_DATE_OLDEST: 'Oldest first',
  };

  // The URL is the source of truth; the inputs below only write to it.
  const current = $derived(readMovieListState(page.url.searchParams));
  let searchText = $state('');
  let yearText = $state('');
  let genreList = $state<Genre[]>([]);
  let genreError = $state(false);

  // Desktop shows the filters in a sidebar; narrower screens open them in a bottom sheet (D59).
  // Only one of the two is on the page at a time, so each input exists once.
  const desktop = new MediaQuery('(min-width: 64rem)');
  let sheetOpen = $state(false);

  const { list, loadMore, retry } = pagedList<MovieSummary>({
    fetchPage: (url, after, signal) => fetchMovies(readMovieListState(url.searchParams), after, signal),
    onExternalNavigation: (url) => {
      const s = readMovieListState(url.searchParams);
      searchText = s.search;
      yearText = s.year?.toString() ?? '';
    },
  });

  loadGenres().then(
    (g) => (genreList = g),
    () => (genreError = true),
  );

  function apply(change: Partial<MovieListState>) {
    // replaceState: typing does not add a history entry per keystroke, and back leaves the list.
    goto(`/${movieListQuery({ ...current, ...change })}`, {
      replaceState: true,
      keepFocus: true,
      noScroll: true,
    });
  }

  // About 300 ms after the last keystroke; the list then cancels any older request (D48, R5).
  const applySearch = debounce((value: string) => apply({ search: value }), 300);
  const applyYear = debounce((value: string) => {
    if (value === '' || /^\d{4}$/.test(value)) apply({ year: value === '' ? null : Number(value) });
  }, 300);
  onDestroy(() => {
    applySearch.cancel();
    applyYear.cancel();
  });

  function toggleGenre(code: string) {
    apply({
      genres: current.genres.includes(code)
        ? current.genres.filter((g) => g !== code)
        : [...current.genres, code],
    });
  }

  const filterCount = $derived(current.genres.length + (current.year != null ? 1 : 0));
  const filtered = $derived(current.search !== '' || filterCount > 0);

  function clearFilters() {
    searchText = '';
    yearText = '';
    applySearch.cancel();
    applyYear.cancel();
    apply({ search: '', genres: [], year: null });
  }

  const meta = (movie: MovieSummary) =>
    [movie.releaseYear, movie.genres.map((g) => g.name).join(', ')].filter(Boolean).join(' · ');
</script>

<svelte:head>
  <title>Movies · Movie Catalogue</title>
</svelte:head>

{#snippet filters()}
  <div class="field">
    <label for="movie-sort">Sort by</label>
    <select
      id="movie-sort"
      value={current.sort}
      onchange={(e) => apply({ sort: e.currentTarget.value as MovieSort })}
    >
      {#each Object.entries(SORT_LABELS) as [value, label] (value)}
        <option {value}>{label}</option>
      {/each}
    </select>
  </div>
  <div class="field">
    <label for="movie-year">Release year</label>
    <input
      id="movie-year"
      type="text"
      inputmode="numeric"
      maxlength="4"
      placeholder="Any"
      aria-describedby="year-hint"
      bind:value={yearText}
      oninput={() => applyYear(yearText.trim())}
    />
    <span id="year-hint" class="visually-hidden">Four digits, for example 2021</span>
  </div>
  <fieldset class="field">
    <legend>Genres</legend>
    {#if genreError}
      <p class="field-error">Genres could not be loaded.</p>
    {/if}
    <div class="genre-list">
      {#each genreList as genre (genre.code)}
        <label class="choice">
          <input
            type="checkbox"
            checked={current.genres.includes(genre.code)}
            onchange={() => toggleGenre(genre.code)}
          />
          {genre.name}
        </label>
      {/each}
    </div>
  </fieldset>
{/snippet}

{#snippet skeletonCard()}
  <li aria-hidden="true">
    <div class="skeleton poster-skeleton"></div>
    <div class="skeleton line"></div>
    <div class="skeleton line short"></div>
  </li>
{/snippet}

<div class="heading">
  <h1>Movies</h1>
  <a class="button primary" href="/movies/new">Add movie</a>
</div>

<form class="searchbar" role="search" onsubmit={(e) => e.preventDefault()}>
  <label for="movie-search" class="visually-hidden">Search titles</label>
  <input
    id="movie-search"
    type="search"
    autocomplete="off"
    placeholder="Search titles"
    bind:value={searchText}
    oninput={() => applySearch(searchText)}
  />
  {#if !desktop.current}
    <button type="button" aria-haspopup="dialog" onclick={() => (sheetOpen = true)}>
      Filters{filterCount ? ` (${filterCount})` : ''}
    </button>
  {/if}
</form>

<div class="browse">
  {#if desktop.current}
    <aside class="sidebar" aria-labelledby="filters-heading">
      <h2 id="filters-heading">Filters</h2>
      {@render filters()}
      {#if filtered}
        <button type="button" onclick={clearFilters}>Clear filters</button>
      {/if}
    </aside>
  {/if}

  <section aria-label="Movie results" aria-busy={list.loading}>
    {#if list.loading}
      <ul class="card-grid">
        {#each Array(10) as _, i (i)}{@render skeletonCard()}{/each}
      </ul>
      <p class="visually-hidden" role="status">Loading movies…</p>
    {:else if list.error}
      <div class="notice error" role="alert">
        <p>Movies could not be loaded. {list.error}</p>
        <button type="button" onclick={retry}>Try again</button>
      </div>
    {:else if list.items.length === 0}
      <div class="notice" role="status">
        {#if filtered}
          <p>No movies match these filters.</p>
          <button type="button" onclick={clearFilters}>Clear filters</button>
        {:else}
          <p>There are no movies yet.</p>
          <a class="button primary" href="/movies/new">Add the first movie</a>
        {/if}
      </div>
    {:else}
      <ul class="card-grid" data-testid="movie-list">
        {#each list.items as movie (movie.id)}
          <li>
            <a class="card" href="/movies/{movie.id}">
              <Poster artwork={movie.artwork} title={movie.title} />
              <span class="title">{movie.title}</span>
              <span class="meta">{meta(movie)}</span>
            </a>
          </li>
        {/each}
        {#if list.loadingMore}
          {#each Array(5) as _, i (i)}{@render skeletonCard()}{/each}
        {/if}
      </ul>
      <ListFooter
        shown={list.items.length}
        total={list.total}
        noun="movies"
        hasNextPage={list.hasNextPage}
        loadingMore={list.loadingMore}
        error={list.moreError}
        onmore={loadMore}
      />
    {/if}
  </section>
</div>

{#if !desktop.current}
  <Dialog open={sheetOpen} title="Filters" sheet onclose={() => (sheetOpen = false)}>
    {@render filters()}
    {#snippet actions()}
      {#if filtered}
        <button type="button" onclick={clearFilters}>Clear filters</button>
      {/if}
      <button type="button" class="primary" onclick={() => (sheetOpen = false)}>Show movies</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .heading {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    margin-bottom: 0.5rem;
  }
  .heading h1 {
    margin: 0;
  }
  /* Stays at the top of the screen while the list scrolls under it. */
  .searchbar {
    position: sticky;
    top: 0;
    z-index: 5;
    display: flex;
    gap: 0.5rem;
    margin: 0 -1rem 1.5rem;
    padding: 0.75rem 1rem;
    background: var(--bg);
    border-bottom: 1px solid var(--line);
  }
  .searchbar input {
    flex: 1;
    min-width: 0;
  }
  .searchbar button {
    flex: none;
  }
  .browse {
    display: grid;
    gap: 2rem;
  }
  @media (min-width: 64rem) {
    .browse {
      grid-template-columns: 14rem minmax(0, 1fr);
    }
  }
  .sidebar h2 {
    margin-top: 0;
  }
  .genre-list .choice {
    border-color: transparent;
  }
  .genre-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.25rem 0.5rem;
  }
  .sidebar .genre-list {
    grid-template-columns: 1fr;
  }
  .card {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    color: inherit;
    text-decoration: none;
  }
  .card :global(.poster) {
    margin-bottom: 0.4rem;
  }
  .card:hover .title {
    text-decoration: underline;
  }
  .title {
    font-family: var(--font-serif);
    font-weight: 600;
    font-size: 1.05rem;
    line-height: 1.25;
  }
  .poster-skeleton {
    aspect-ratio: 2 / 3;
  }
  .line {
    height: 1rem;
    margin-top: 0.6rem;
    width: 80%;
  }
  .line.short {
    height: 0.75rem;
    margin-top: 0.4rem;
    width: 50%;
  }
</style>
