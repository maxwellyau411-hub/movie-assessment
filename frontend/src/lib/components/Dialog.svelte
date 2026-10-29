<script lang="ts">
  import type { Snippet } from 'svelte';

  /**
   * A modal built on the native <dialog>: the browser handles focus, Escape and the backdrop.
   * `open` drives showModal()/close(); closing by Escape calls `onclose`.
   * `sheet` slides it up from the bottom of the screen instead (the phone filters).
   */
  let {
    open,
    title,
    onclose,
    children,
    actions,
    sheet = false,
  }: {
    open: boolean;
    title: string;
    onclose: () => void;
    children: Snippet;
    actions: Snippet;
    sheet?: boolean;
  } = $props();

  const titleId = $props.id();
  let dialog: HTMLDialogElement;

  $effect(() => {
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  });
</script>

<dialog bind:this={dialog} class:sheet aria-labelledby={titleId} {onclose}>
  <h2 id={titleId}>{title}</h2>
  <div class="body">{@render children()}</div>
  <div class="actions">{@render actions()}</div>
</dialog>

<style>
  h2 {
    margin-top: 0;
  }
  .actions {
    margin-top: 1.25rem;
    justify-content: flex-end;
  }
  .sheet {
    width: 100%;
    max-width: 100%;
    max-height: 85dvh;
    margin: auto 0 0;
    padding: 0;
    border-width: 1px 0 0;
    border-radius: var(--radius) var(--radius) 0 0;
    display: flex;
    flex-direction: column;
  }
  .sheet:not([open]) {
    display: none;
  }
  .sheet h2 {
    margin: 0;
    padding: 1rem 1rem 0.5rem;
  }
  .sheet .body {
    overflow-y: auto;
    padding: 0.5rem 1rem;
  }
  .sheet .actions {
    margin: 0;
    padding: 0.75rem 1rem calc(0.75rem + env(safe-area-inset-bottom, 0px));
    border-top: 1px solid var(--line);
  }
  @media (prefers-reduced-motion: no-preference) {
    .sheet[open] {
      animation: slide-up 0.2s ease-out;
    }
  }
  @keyframes slide-up {
    from {
      transform: translateY(100%);
    }
  }
</style>
