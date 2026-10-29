<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import PersonForm from '$lib/components/PersonForm.svelte';
  import { showToast } from '$lib/toast.svelte';
  import type { PersonCategory } from '$lib/types';

  // "Add person" on the Cast or Creators list pre-ticks that category.
  const category = page.url.searchParams.get('category');
  const defaultCategory: PersonCategory | null =
    category === 'CAST' || category === 'CREATOR' ? category : null;
</script>

<svelte:head>
  <title>Add person · Movie Catalogue</title>
</svelte:head>

<p><a href={defaultCategory === 'CREATOR' ? '/creators' : '/cast'}>← Back</a></p>
<h1>Add a person</h1>
<PersonForm
  person={null}
  {defaultCategory}
  cancelHref={defaultCategory === 'CREATOR' ? '/creators' : '/cast'}
  onsaved={(person) => {
    showToast(`${person.name} was created.`);
    goto(`/people/${person.id}`);
  }}
/>
