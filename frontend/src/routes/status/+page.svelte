<script lang="ts">
  import { fetchServiceStatus, type ServiceStatus } from '$lib/api';
  import { describeError } from '$lib/errors';

  let status = $state<ServiceStatus | null>(null);
  let error = $state<string | null>(null);
  let loading = $state(true);

  async function load() {
    loading = true;
    error = null;
    try {
      status = await fetchServiceStatus();
    } catch (e) {
      status = null;
      error = describeError(e);
    } finally {
      loading = false;
    }
  }

  load();
</script>

<svelte:head>
  <title>System status · Movie Catalogue</title>
</svelte:head>

<h1>System status</h1>
<p class="muted">The browser asks Catalogue Service over GraphQL; Catalogue asks Media Service over gRPC.</p>

<div role="status" aria-live="polite" aria-busy={loading}>
  {#if loading}
    <p>Checking services…</p>
  {:else if error}
    <p class="notice error">Could not load status: {error}</p>
  {:else if status}
    <ul>
      <li>Catalogue Service: <strong data-testid="catalogue-status">{status.catalogue}</strong></li>
      <li>
        Media Service (via gRPC): <strong data-testid="media-service-status">{status.mediaService}</strong>
      </li>
    </ul>
  {/if}
</div>
<button type="button" onclick={load} disabled={loading}>Refresh</button>
