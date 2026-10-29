<script lang="ts">
  import { addCastCredit, addCreatorCredit, removeCastCredit, removeCreatorCredit } from '$lib/api';
  import { describeError } from '$lib/errors';
  import { CREATOR_ROLES, type CreatorRole } from '$lib/listState';
  import { optional } from '$lib/text';
  import { showToast } from '$lib/toast.svelte';
  import type { Movie, PersonSummary } from '$lib/types';
  import Avatar from './Avatar.svelte';
  import PersonPicker from './PersonPicker.svelte';

  /** Linking and unlinking credits. After each change the page reloads the movie (`onchanged`). */
  let { movie, onchanged }: { movie: Movie; onchanged: () => Promise<void> } = $props();

  const ROLE_LABELS: Record<CreatorRole, string> = {
    DIRECTOR: 'Director',
    WRITER: 'Writer',
    PRODUCER: 'Producer',
    COMPOSER: 'Composer',
  };

  let castPerson = $state<PersonSummary | null>(null);
  let character = $state('');
  let creatorPerson = $state<PersonSummary | null>(null);
  let role = $state<CreatorRole | ''>('');
  let busy = $state(false);

  async function run(action: () => Promise<unknown>, success: string) {
    busy = true;
    try {
      await action();
      await onchanged();
      showToast(success);
      return true;
    } catch (e) {
      showToast(describeError(e), 'error');
      return false;
    } finally {
      busy = false;
    }
  }

  async function addCast() {
    if (!castPerson) return;
    const person = castPerson;
    if (
      await run(
        () => addCastCredit(movie.id, person.id, optional(character)),
        `${person.name} added to the cast.`,
      )
    ) {
      castPerson = null;
      character = '';
    }
  }

  async function addCreator() {
    if (!creatorPerson) return;
    const person = creatorPerson;
    if (
      await run(
        () => addCreatorCredit(movie.id, person.id, role || null),
        `${person.name} added as a creator.`,
      )
    ) {
      creatorPerson = null;
      role = '';
    }
  }
</script>

<section aria-labelledby="cast-heading">
  <h2 id="cast-heading">Cast</h2>
  {#if movie.cast.length === 0}
    <p class="muted">No cast yet.</p>
  {:else}
    <ul class="rows credits">
      {#each movie.cast as credit (credit.id)}
        <li>
          <Avatar name={credit.person.name} size={2.25} />
          <span class="who">
            <a href="/people/{credit.person.id}">{credit.person.name}</a>
            {#if credit.characterName}<span class="muted"> as {credit.characterName}</span>{/if}
          </span>
          <button
            type="button"
            disabled={busy}
            aria-label="Remove {credit.person.name} from the cast"
            onclick={() =>
              run(() => removeCastCredit(credit.id), `${credit.person.name} removed from the cast.`)}
            >Remove</button
          >
        </li>
      {/each}
    </ul>
  {/if}
  <form
    class="add"
    onsubmit={(e) => {
      e.preventDefault();
      addCast();
    }}
  >
    <div class="field">
      <label for="cast-person">Add a cast member</label>
      <PersonPicker id="cast-person" category="CAST" bind:selected={castPerson} />
    </div>
    <div class="field">
      <label for="character">Character <span class="muted">(optional)</span></label>
      <input id="character" type="text" maxlength="200" bind:value={character} />
    </div>
    <button type="submit" disabled={!castPerson || busy}>Add to cast</button>
  </form>
</section>

<section aria-labelledby="creators-heading">
  <h2 id="creators-heading">Creators</h2>
  {#if movie.creators.length === 0}
    <p class="muted">No creators yet.</p>
  {:else}
    <ul class="rows credits">
      {#each movie.creators as credit (credit.id)}
        <li>
          <Avatar name={credit.person.name} size={2.25} />
          <span class="who">
            <a href="/people/{credit.person.id}">{credit.person.name}</a>
            <span class="meta"> · {credit.role ? ROLE_LABELS[credit.role] : 'Unspecified'}</span>
          </span>
          <button
            type="button"
            disabled={busy}
            aria-label="Remove {credit.person.name} as {credit.role ? ROLE_LABELS[credit.role] : 'creator'}"
            onclick={() =>
              run(() => removeCreatorCredit(credit.id), `${credit.person.name} removed from the creators.`)}
            >Remove</button
          >
        </li>
      {/each}
    </ul>
  {/if}
  <form
    class="add"
    onsubmit={(e) => {
      e.preventDefault();
      addCreator();
    }}
  >
    <div class="field">
      <label for="creator-person">Add a creator</label>
      <PersonPicker id="creator-person" category="CREATOR" bind:selected={creatorPerson} />
    </div>
    <div class="field">
      <label for="role">Role <span class="muted">(optional)</span></label>
      <select id="role" bind:value={role}>
        <option value="">Unspecified</option>
        {#each CREATOR_ROLES as r (r)}
          <option value={r}>{ROLE_LABELS[r]}</option>
        {/each}
      </select>
    </div>
    <button type="submit" disabled={!creatorPerson || busy}>Add creator</button>
  </form>
</section>

<style>
  .credits {
    margin-bottom: 1rem;
  }
  .credits li {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0;
  }
  .who {
    flex: 1;
    min-width: 0;
  }
  .who a {
    font-weight: 600;
  }
  .add {
    display: grid;
    gap: 0 1rem;
    align-items: end;
    padding: 1rem 1rem 0;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .add button {
    margin-bottom: 1rem;
    justify-self: start;
  }
  @media (min-width: 48rem) {
    .add {
      grid-template-columns: 2fr 1fr auto;
    }
  }
</style>
