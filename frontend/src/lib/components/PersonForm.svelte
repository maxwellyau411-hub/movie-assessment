<script lang="ts">
  import { savePerson } from '$lib/api';
  import { describeError, isStale } from '$lib/errors';
  import { GraphQLRequestError } from '$lib/graphql';
  import { optional, sentence } from '$lib/text';
  import { showToast } from '$lib/toast.svelte';
  import type { Person, PersonCategory, PersonInput, PersonRef } from '$lib/types';
  import SaveDuplicates from './SaveDuplicates.svelte';

  /** Create (person = null) or edit. `defaultCategory` pre-ticks Cast or Creator on create. */
  let {
    person,
    defaultCategory = null,
    onsaved,
    cancelHref,
  }: {
    person: Person | null;
    defaultCategory?: PersonCategory | null;
    onsaved: (person: Person) => void;
    cancelHref: string;
  } = $props();

  // svelte-ignore state_referenced_locally
  const initial = person;
  let name = $state(initial?.name ?? '');
  let dateOfBirth = $state(initial?.dateOfBirth ?? '');
  let biography = $state(initial?.biography ?? '');
  // svelte-ignore state_referenced_locally
  let categories = $state<PersonCategory[]>(
    initial?.categories ?? (defaultCategory ? [defaultCategory] : []),
  );

  let saving = $state(false);
  let fieldErrors = $state<Record<string, string>>({});
  // A stale version stays on the form, with its Reload button; other errors are toasts (D59).
  let staleError = $state<string | null>(null);
  let duplicates = $state<{ message: string; candidates: PersonRef[] } | null>(null);

  // The browser's own today; the server allows dates up to today in the furthest-ahead time zone.
  const today = new Date().toLocaleDateString('en-CA');

  function input(): PersonInput {
    return { name, dateOfBirth: dateOfBirth || null, biography: optional(biography), categories };
  }

  function precheck(): Record<string, string> {
    const errors: Record<string, string> = {};
    if (name.trim() === '') errors.name = 'Enter a name.';
    if (categories.length === 0) errors.categories = 'Choose Cast, Creator, or both.';
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
      const result = await savePerson(person?.id ?? null, person?.version ?? null, input(), confirmDuplicate);
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
    <label for="name">Name</label>
    <input
      id="name"
      type="text"
      maxlength="200"
      bind:value={name}
      aria-invalid={invalid('name')}
      aria-describedby={fieldErrors.name ? 'name-error' : undefined}
    />
    {#if fieldErrors.name}<p class="field-error" id="name-error">{fieldErrors.name}</p>{/if}
  </div>

  <fieldset class="field" aria-describedby={fieldErrors.categories ? 'categories-error' : undefined}>
    <legend>Categories</legend>
    <div class="choices">
      <label class="choice"><input type="checkbox" value="CAST" bind:group={categories} /> Cast</label>
      <label class="choice"><input type="checkbox" value="CREATOR" bind:group={categories} /> Creator</label>
    </div>
    {#if fieldErrors.categories}<p class="field-error" id="categories-error">{fieldErrors.categories}</p>{/if}
  </fieldset>

  <div class="field">
    <label for="dob">Date of birth <span class="muted">(optional)</span></label>
    <input
      id="dob"
      type="date"
      max={today}
      bind:value={dateOfBirth}
      aria-invalid={invalid('dateOfBirth')}
      aria-describedby={fieldErrors.dateOfBirth ? 'dob-error' : undefined}
    />
    {#if fieldErrors.dateOfBirth}<p class="field-error" id="dob-error">{fieldErrors.dateOfBirth}</p>{/if}
  </div>

  <div class="field">
    <label for="biography">Biography <span class="muted">(optional)</span></label>
    <textarea
      id="biography"
      maxlength="5000"
      bind:value={biography}
      aria-invalid={invalid('biography')}
      aria-describedby={fieldErrors.biography ? 'bio-error' : undefined}></textarea>
    {#if fieldErrors.biography}<p class="field-error" id="bio-error">{fieldErrors.biography}</p>{/if}
  </div>

  <div class="actions">
    <button type="submit" class="primary" disabled={saving} aria-busy={saving}>
      {saving ? 'Saving…' : person ? 'Save changes' : 'Create person'}
    </button>
    <a class="button" href={cancelHref}>Cancel</a>
  </div>
</form>

<SaveDuplicates
  open={duplicates != null}
  message={duplicates?.message ?? ''}
  candidates={(duplicates?.candidates ?? []).map((p) => ({
    id: p.id,
    label: p.name,
    href: `/people/${p.id}`,
  }))}
  {saving}
  onconfirm={() => save(true)}
  oncancel={() => (duplicates = null)}
/>
