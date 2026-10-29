<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { fetchPerson } from '$lib/api';
  import PersonForm from '$lib/components/PersonForm.svelte';
  import { describeError } from '$lib/errors';
  import { showToast } from '$lib/toast.svelte';
  import type { Person } from '$lib/types';

  let person = $state<Person | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  async function load(id: string) {
    loading = true;
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
    load(page.params.id!);
  });
</script>

<svelte:head>
  <title>Edit {person?.name ?? 'person'} · Movie Catalogue</title>
</svelte:head>

<p><a href="/people/{page.params.id}">← Back to the person</a></p>

{#if loading}
  <div class="skeleton" style="height: 20rem" aria-busy="true"></div>
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
  <h1>Edit {person.name}</h1>
  <PersonForm
    {person}
    cancelHref="/people/{person.id}"
    onsaved={(saved) => {
      showToast('Changes saved.');
      goto(`/people/${saved.id}`);
    }}
  />
{/if}
