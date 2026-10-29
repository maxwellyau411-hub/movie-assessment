<script lang="ts">
  import Dialog from './Dialog.svelte';

  /**
   * The duplicate warning (D34): nothing was saved yet. The user can open the similar records
   * (in a new tab, so the form stays as it is), cancel, or save anyway.
   */
  let {
    open,
    message,
    candidates,
    saving,
    onconfirm,
    oncancel,
  }: {
    open: boolean;
    message: string;
    candidates: { id: string; label: string; href: string }[];
    saving: boolean;
    onconfirm: () => void;
    oncancel: () => void;
  } = $props();
</script>

<Dialog {open} title="Possible duplicate" onclose={oncancel}>
  <p>{message}</p>
  <ul>
    {#each candidates as candidate (candidate.id)}
      <li><a href={candidate.href} target="_blank" rel="noopener">{candidate.label}</a></li>
    {/each}
  </ul>
  {#snippet actions()}
    <button type="button" onclick={oncancel} disabled={saving}>Cancel</button>
    <button type="button" class="primary" onclick={onconfirm} disabled={saving}>
      {saving ? 'Saving…' : 'Save anyway'}
    </button>
  {/snippet}
</Dialog>
