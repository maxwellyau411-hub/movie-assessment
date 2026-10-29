<script lang="ts">
  import type { Snippet } from 'svelte';
  import Dialog from './Dialog.svelte';

  let {
    open,
    title,
    deleting,
    error,
    onconfirm,
    oncancel,
    children,
  }: {
    open: boolean;
    title: string;
    deleting: boolean;
    error: string | null;
    onconfirm: () => void;
    oncancel: () => void;
    children: Snippet;
  } = $props();
</script>

<Dialog {open} {title} onclose={oncancel}>
  {@render children()}
  {#if error}<div class="notice error" role="alert">{error}</div>{/if}
  {#snippet actions()}
    <button type="button" onclick={oncancel} disabled={deleting}>Cancel</button>
    <button type="button" class="danger" onclick={onconfirm} disabled={deleting} aria-busy={deleting}>
      {deleting ? 'Deleting…' : 'Delete'}
    </button>
  {/snippet}
</Dialog>
