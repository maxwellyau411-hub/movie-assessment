<script lang="ts">
  import { fetchPeople } from '$lib/api';
  import { debounce, latestOnly, STALE } from '$lib/async';
  import { describeError } from '$lib/errors';
  import type { PersonCategory, PersonSummary } from '$lib/types';
  import { onDestroy } from 'svelte';

  /** Finds a person to credit. Only people in the right category are offered (Cast or Creator). */
  let {
    category,
    id,
    selected = $bindable(null),
  }: { category: PersonCategory; id: string; selected: PersonSummary | null } = $props();

  let text = $state('');
  let results = $state<PersonSummary[]>([]);
  let searching = $state(false);
  let error = $state<string | null>(null);
  let searched = $state(false);
  const latest = latestOnly();

  const search = debounce(async (value: string) => {
    if (value.trim() === '') {
      latest.cancel();
      results = [];
      searched = false;
      searching = false;
      return;
    }
    searching = true;
    error = null;
    try {
      const page = await latest.run((signal) =>
        fetchPeople(category, { search: value, sort: 'NAME_ASC', role: null }, null, signal, 8),
      );
      if (page === STALE) return;
      results = page.nodes;
      searched = true;
    } catch (e) {
      error = describeError(e);
    }
    searching = false;
  }, 300);

  /** Clears the search too, so the picker starts empty again once the parent resets `selected`. */
  function choose(person: PersonSummary) {
    selected = person;
    text = '';
    results = [];
    searched = false;
  }

  onDestroy(() => {
    search.cancel();
    latest.cancel();
  });
</script>

{#if selected}
  <p class="chosen">
    <strong>{selected.name}</strong>
    <button
      type="button"
      onclick={() => {
        selected = null;
        text = '';
        results = [];
        searched = false;
      }}>Change</button
    >
  </p>
{:else}
  <input
    {id}
    type="search"
    autocomplete="off"
    placeholder={category === 'CAST' ? 'Search cast members' : 'Search creators'}
    bind:value={text}
    oninput={() => search(text)}
  />
  <div aria-live="polite">
    {#if searching}
      <p class="muted">Searching…</p>
    {:else if error}
      <p class="field-error">{error}</p>
    {:else if searched && results.length === 0}
      <p class="muted">
        No {category === 'CAST' ? 'cast members' : 'creators'} match. People need the {category === 'CAST'
          ? 'Cast'
          : 'Creator'} category to be credited here.
      </p>
    {/if}
  </div>
  {#if results.length}
    <ul class="results">
      {#each results as person (person.id)}
        <li>
          <button type="button" onclick={() => choose(person)}>
            {person.name}{person.age != null ? ` · ${person.age}` : ''}
          </button>
        </li>
      {/each}
    </ul>
  {/if}
{/if}

<style>
  .chosen {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin: 0;
  }
  .results {
    list-style: none;
    padding: 0;
    margin: 0.25rem 0 0;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .results li + li {
    border-top: 1px solid var(--line);
  }
  .results button {
    width: 100%;
    justify-content: flex-start;
    border: 0;
    border-radius: 0;
  }
</style>
