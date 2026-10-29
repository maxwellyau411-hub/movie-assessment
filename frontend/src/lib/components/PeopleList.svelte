<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { fetchPeople } from '$lib/api';
  import { debounce } from '$lib/async';
  import {
    CREATOR_ROLES,
    personListQuery,
    readPersonListState,
    type CreatorRole,
    type PersonListState,
    type PersonSort,
  } from '$lib/listState';
  import { pagedList } from '$lib/pagedList.svelte';
  import type { PersonCategory, PersonSummary } from '$lib/types';
  import { onDestroy } from 'svelte';
  import Avatar from './Avatar.svelte';
  import ListFooter from './ListFooter.svelte';

  /** The Cast and Creators lists: the same screen, one category each. Creators can filter by role. */
  let { category }: { category: PersonCategory } = $props();

  const path = $derived(category === 'CAST' ? '/cast' : '/creators');
  const heading = $derived(category === 'CAST' ? 'Cast' : 'Creators');
  const ROLE_LABELS: Record<CreatorRole, string> = {
    DIRECTOR: 'Directors',
    WRITER: 'Writers',
    PRODUCER: 'Producers',
    COMPOSER: 'Composers',
  };

  const current = $derived(readPersonListState(page.url.searchParams));
  let searchText = $state('');

  const { list, loadMore, retry } = pagedList<PersonSummary>({
    fetchPage: (url, after, signal) =>
      fetchPeople(category, readPersonListState(url.searchParams), after, signal),
    onExternalNavigation: (url) => (searchText = readPersonListState(url.searchParams).search),
  });

  function apply(change: Partial<PersonListState>) {
    goto(`${path}${personListQuery({ ...current, ...change })}`, {
      replaceState: true,
      keepFocus: true,
      noScroll: true,
    });
  }

  const applySearch = debounce((value: string) => apply({ search: value }), 300);
  onDestroy(() => applySearch.cancel());

  const filtered = $derived(current.search !== '' || current.role != null);
</script>

<svelte:head>
  <title>{heading} · Movie Catalogue</title>
</svelte:head>

<div class="heading">
  <h1>{heading}</h1>
  <a class="button primary" href="/people/new?category={category}">Add person</a>
</div>
<p class="muted">
  Everyone in the {category === 'CAST' ? 'Cast' : 'Creator'} category, credited in a movie or not.
</p>

<form class="filters" role="search" onsubmit={(e) => e.preventDefault()}>
  <div class="field search">
    <label for="person-search">Search names</label>
    <input
      id="person-search"
      type="search"
      autocomplete="off"
      bind:value={searchText}
      oninput={() => applySearch(searchText)}
    />
  </div>
  <div class="field">
    <label for="person-sort">Sort by</label>
    <select
      id="person-sort"
      value={current.sort}
      onchange={(e) => apply({ sort: e.currentTarget.value as PersonSort })}
    >
      <option value="NAME_ASC">Name A–Z</option>
      <option value="NAME_DESC">Name Z–A</option>
    </select>
  </div>
  {#if category === 'CREATOR'}
    <div class="field">
      <label for="person-role">Role</label>
      <select
        id="person-role"
        value={current.role ?? ''}
        onchange={(e) => apply({ role: (e.currentTarget.value || null) as CreatorRole | null })}
      >
        <option value="">Any role</option>
        {#each CREATOR_ROLES as role (role)}
          <option value={role}>{ROLE_LABELS[role]}</option>
        {/each}
      </select>
    </div>
  {/if}
</form>

<section aria-label="{heading} results" aria-busy={list.loading}>
  {#if list.loading}
    <ul class="people" aria-hidden="true">
      {#each Array(6) as _, i (i)}<li><div class="skeleton row"></div></li>{/each}
    </ul>
    <p class="visually-hidden" role="status">Loading…</p>
  {:else if list.error}
    <div class="notice error" role="alert">
      <p>The list could not be loaded. {list.error}</p>
      <button type="button" onclick={retry}>Try again</button>
    </div>
  {:else if list.items.length === 0}
    <div class="notice" role="status">
      <p>{filtered ? 'Nobody matches these filters.' : 'Nobody here yet.'}</p>
      {#if filtered}
        <button
          type="button"
          onclick={() => {
            searchText = '';
            applySearch.cancel();
            apply({ search: '', role: null });
          }}>Clear filters</button
        >
      {/if}
    </div>
  {:else}
    <ul class="people" data-testid="people-list">
      {#each list.items as person (person.id)}
        <li>
          <a href="/people/{person.id}">
            <Avatar name={person.name} />
            <span class="name">{person.name}</span>
            <span class="meta">{person.age != null ? `${person.age} years` : ''}</span>
          </a>
        </li>
      {/each}
    </ul>
    <ListFooter
      shown={list.items.length}
      total={list.total}
      noun="people"
      hasNextPage={list.hasNextPage}
      loadingMore={list.loadingMore}
      error={list.moreError}
      onmore={loadMore}
    />
  {/if}
</section>

<style>
  .heading {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
  }
  .heading h1 {
    margin: 0;
  }
  .filters {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 0.75rem;
    margin-top: 1rem;
  }
  .filters .search {
    grid-column: 1 / -1;
  }
  @media (min-width: 48rem) {
    .filters {
      grid-template-columns: 2fr 1fr 1fr;
    }
    .filters .search {
      grid-column: auto;
    }
  }
  /* Rows separated by thin lines; two columns on wider screens. */
  .people {
    list-style: none;
    padding: 0;
    margin: 0;
    display: grid;
    column-gap: 2rem;
    border-top: 1px solid var(--line);
  }
  @media (min-width: 48rem) {
    .people {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
  .people li {
    border-bottom: 1px solid var(--line);
  }
  .people a {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.6rem 0.5rem;
    color: inherit;
    text-decoration: none;
  }
  .people a:hover {
    background: var(--tint);
  }
  .people a:hover .name {
    text-decoration: underline;
  }
  .name {
    flex: 1;
    font-weight: 600;
  }
  .row {
    height: 2.75rem;
    margin: 0.6rem 0.5rem;
  }
</style>
