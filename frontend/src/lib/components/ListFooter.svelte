<script lang="ts">
  import type { TotalCount } from '$lib/types';

  let {
    shown,
    total,
    noun,
    hasNextPage,
    loadingMore,
    error,
    onmore,
  }: {
    shown: number;
    total: TotalCount | null;
    noun: string;
    hasNextPage: boolean;
    loadingMore: boolean;
    error: string | null;
    onmore: () => void;
  } = $props();

  // Counting stops at 10,000 on the server (D45).
  const totalText = $derived(total ? `${total.count.toLocaleString('en')}${total.capped ? '+' : ''}` : null);
</script>

<div class="footer">
  <p class="muted" aria-live="polite">
    Showing {shown.toLocaleString('en')}{totalText ? ` of ${totalText}` : ''}
    {noun}
  </p>
  {#if error}
    <p class="notice error" role="alert">{error}</p>
  {/if}
  {#if hasNextPage}
    <button type="button" onclick={onmore} disabled={loadingMore} aria-busy={loadingMore}>
      {loadingMore ? 'Loading…' : error ? 'Try again' : 'Load more'}
    </button>
  {/if}
</div>

<style>
  .footer {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.5rem;
    margin-top: 1.5rem;
  }
</style>
