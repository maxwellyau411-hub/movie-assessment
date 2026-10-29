<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { deletePerson, fetchPerson } from '$lib/api';
  import Avatar from '$lib/components/Avatar.svelte';
  import ConfirmDelete from '$lib/components/ConfirmDelete.svelte';
  import { describeError, isStale } from '$lib/errors';
  import { GraphQLRequestError } from '$lib/graphql';
  import { showToast } from '$lib/toast.svelte';
  import type { MovieRef, Person } from '$lib/types';

  const ROLE_LABELS: Record<string, string> = {
    DIRECTOR: 'Director',
    WRITER: 'Writer',
    PRODUCER: 'Producer',
    COMPOSER: 'Composer',
  };

  let person = $state<Person | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  let confirming = $state(false);
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);
  // PERSON_HAS_CREDITS lists (up to 10 of) the movies that still credit this person.
  let blockingMovies = $state<{ count: number; movies: MovieRef[] } | null>(null);

  async function load(id: string) {
    error = null;
    try {
      person = await fetchPerson(id);
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

  const listPath = $derived(person?.categories.includes('CAST') ? '/cast' : '/creators');

  async function remove() {
    if (!person) return;
    deleting = true;
    deleteError = null;
    blockingMovies = null;
    try {
      await deletePerson(person.id, person.version);
      showToast(`${person.name} was deleted.`);
      await goto(listPath);
    } catch (e) {
      if (e instanceof GraphQLRequestError && e.code === 'NOT_FOUND') {
        showToast('That person had already been deleted.');
        await goto(listPath);
        return;
      }
      if (e instanceof GraphQLRequestError && e.reason === 'PERSON_HAS_CREDITS') {
        blockingMovies = {
          count: Number(e.extensions?.movieCount ?? 0),
          movies: (e.extensions?.movies as MovieRef[] | undefined) ?? [],
        };
      }
      deleteError = isStale(e)
        ? 'Someone changed this person since you opened it. Close this, check the latest version, then delete again.'
        : describeError(e);
      if (isStale(e)) await load(person.id);
    } finally {
      deleting = false;
    }
  }
</script>

<svelte:head>
  <title>{person?.name ?? 'Person'} · Movie Catalogue</title>
</svelte:head>

{#if loading}
  <div class="detail" aria-busy="true">
    <div class="picture"><div class="skeleton portrait"></div></div>
    <div><div class="skeleton" style="height: 2.5rem; width: 50%"></div></div>
  </div>
  <p class="visually-hidden" role="status">Loading person…</p>
{:else if error}
  <div class="notice error" role="alert">
    <p>This person could not be loaded. {error}</p>
    <button type="button" onclick={() => load(page.params.id!)}>Try again</button>
  </div>
{:else if !person}
  <div class="notice" role="status">
    <p>This person does not exist. They may have been deleted.</p>
    <a class="button" href="/cast">Back to the cast list</a>
  </div>
{:else}
  <p><a href={listPath}>← {listPath === '/cast' ? 'Cast' : 'Creators'}</a></p>
  <div class="detail">
    <div class="picture portrait-frame"><Avatar name={person.name} size={9} /></div>
    <div>
      <h1>{person.name}</h1>
      <p class="meta">
        {person.categories.map((c) => (c === 'CAST' ? 'Cast' : 'Creator')).join(' and ')}
        {#if person.dateOfBirth}
          · born {new Date(`${person.dateOfBirth}T00:00:00Z`).toLocaleDateString('en-GB', {
            dateStyle: 'long',
            timeZone: 'UTC',
          })} ({person.age} years)
        {/if}
      </p>
      {#if person.biography}<p class="bio">{person.biography}</p>{/if}

      <div class="actions">
        <a class="button primary" href="/people/{person.id}/edit">Edit</a>
        <button
          type="button"
          onclick={() => {
            deleteError = null;
            blockingMovies = null;
            confirming = true;
          }}>Delete</button
        >
      </div>

      {#if person.categories.includes('CAST')}
        <h2>Cast credits</h2>
        {#if person.castCredits.length}
          <ul class="rows credits">
            {#each person.castCredits as credit (credit.id)}
              <li>
                <a href="/movies/{credit.movie.id}">{credit.movie.title}</a>
                <span class="meta">{credit.movie.releaseYear ?? 'year unknown'}</span>
                {#if credit.characterName}<span class="muted">as {credit.characterName}</span>{/if}
              </li>
            {/each}
          </ul>
        {:else}<p class="muted">No cast credits.</p>{/if}
      {/if}
      {#if person.categories.includes('CREATOR')}
        <h2>Creator credits</h2>
        {#if person.creatorCredits.length}
          <ul class="rows credits">
            {#each person.creatorCredits as credit (credit.id)}
              <li>
                <a href="/movies/{credit.movie.id}">{credit.movie.title}</a>
                <span class="meta">{credit.movie.releaseYear ?? 'year unknown'}</span>
                <span class="muted">{credit.role ? ROLE_LABELS[credit.role] : 'Unspecified'}</span>
              </li>
            {/each}
          </ul>
        {:else}<p class="muted">No creator credits.</p>{/if}
      {/if}
    </div>
  </div>

  <ConfirmDelete
    open={confirming}
    title="Delete this person?"
    {deleting}
    error={deleteError}
    onconfirm={remove}
    oncancel={() => (confirming = false)}
  >
    <p>{person.name} will be deleted. This cannot be undone.</p>
    {#if blockingMovies}
      <p>Remove these credits first, on each movie's page:</p>
      <ul>
        {#each blockingMovies.movies as movie (movie.id)}
          <li><a href="/movies/{movie.id}">{movie.title}</a> ({movie.releaseYear ?? 'year unknown'})</li>
        {/each}
      </ul>
      {#if blockingMovies.count > blockingMovies.movies.length}
        <p class="muted">…and {blockingMovies.count - blockingMovies.movies.length} more.</p>
      {/if}
    {/if}
  </ConfirmDelete>
{/if}

<style>
  /* On a phone the circle sits above the name; on a desktop it fills the left column's width. */
  .portrait-frame :global(.avatar) {
    width: min(100%, 9rem);
    height: auto;
    aspect-ratio: 1;
  }
  @media (min-width: 48rem) {
    .portrait-frame :global(.avatar) {
      width: 100%;
      font-size: 4rem;
    }
  }
  .portrait {
    width: 9rem;
    aspect-ratio: 1;
    border-radius: 50%;
  }
  .bio {
    white-space: pre-line;
    max-width: 42rem;
  }
  .credits li {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 0.25rem 0.75rem;
    padding: 0.6rem 0;
  }
  .credits a {
    font-weight: 600;
  }
</style>
