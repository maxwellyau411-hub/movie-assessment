<script lang="ts">
  import { dismissToast, toasts } from '$lib/toast.svelte';

  // Two live regions that are always on the page, so screen readers announce what appears in
  // them: messages politely, errors straight away.
  const infos = $derived(toasts.filter((t) => t.kind === 'info'));
  const errors = $derived(toasts.filter((t) => t.kind === 'error'));
</script>

<div class="toasts">
  <div aria-live="polite">
    {#each infos as toast (toast.id)}
      <p class="toast">{toast.message}</p>
    {/each}
  </div>
  <div aria-live="assertive">
    {#each errors as toast (toast.id)}
      <div class="toast error">
        <p>{toast.message}</p>
        <button type="button" onclick={() => dismissToast(toast.id)}>Dismiss</button>
      </div>
    {/each}
  </div>
</div>

<style>
  .toasts {
    position: fixed;
    inset-inline: 0;
    bottom: 0;
    z-index: 10;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.5rem;
    padding: 0 1rem calc(1rem + env(safe-area-inset-bottom, 0px));
    /* The empty strip must not block clicks on the page under it. */
    pointer-events: none;
  }
  .toasts > div {
    display: contents;
  }
  .toast {
    pointer-events: auto;
    display: flex;
    align-items: center;
    gap: 0.75rem;
    width: min(30rem, 100%);
    margin: 0;
    padding: 0.75rem 1rem;
    border-radius: var(--radius);
    background: var(--text);
    color: var(--bg);
  }
  .toast.error {
    background: var(--danger);
    color: var(--on-danger);
  }
  .toast p {
    flex: 1;
    margin: 0;
  }
  .toast button {
    min-height: 2.75rem;
    border-color: currentColor;
    color: inherit;
  }
  .toast button:hover {
    background: color-mix(in srgb, currentColor 15%, transparent);
  }
</style>
