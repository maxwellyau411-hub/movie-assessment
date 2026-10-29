<script lang="ts">
  import { describeError } from '$lib/errors';
  import { GraphQLRequestError } from '$lib/graphql';
  import { sentence } from '$lib/text';
  import { showToast } from '$lib/toast.svelte';
  import type { Artwork } from '$lib/types';
  import { ACCEPTED_TYPES, precheck, uploadPoster, type UploadedPoster } from '$lib/upload';
  import { onDestroy } from 'svelte';
  import Poster from './Poster.svelte';

  /**
   * Choose → preview → upload with a real byte progress bar → "Processing" while the server checks
   * and stores the image (no made-up percentage for that part) → done. Cancel and retry included.
   *
   * With `movieId` null (creating a movie) it only holds the chosen file; the page calls
   * `start(id)` once the movie exists.
   */
  let {
    movieId,
    artwork,
    title,
    onuploaded,
    oncancelled,
  }: {
    movieId: string | null;
    artwork: Artwork | null;
    title: string;
    onuploaded: (poster: UploadedPoster) => void;
    /** A cancel after the bytes arrived may still have been saved; the page should re-read the movie. */
    oncancelled?: () => void;
  } = $props();

  type Phase = 'idle' | 'selected' | 'uploading' | 'processing' | 'done' | 'error';
  let phase = $state<Phase>('idle');
  let file = $state<File | null>(null);
  let previewUrl = $state<string | null>(null);
  let loaded = $state(0);
  let total = $state(0);
  // A refused file is a field error, shown in red next to the chooser; other problems are toasts.
  let fileError = $state<string | null>(null);
  let input: HTMLInputElement;
  let controller: AbortController | null = null;

  const percent = $derived(total ? Math.round((loaded / total) * 100) : 0);
  const busy = $derived(phase === 'uploading' || phase === 'processing');

  function setPreview(next: File | null) {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = next ? URL.createObjectURL(next) : null;
  }

  function choose(event: Event & { currentTarget: HTMLInputElement }) {
    const chosen = event.currentTarget.files?.[0] ?? null;
    fileError = chosen ? precheck(chosen) : null;
    if (!chosen || fileError) {
      file = null;
      setPreview(null);
      phase = chosen ? 'error' : 'idle';
      return;
    }
    file = chosen;
    setPreview(chosen);
    phase = 'selected';
  }

  function clear() {
    file = null;
    setPreview(null);
    fileError = null;
    phase = 'idle';
    input.value = '';
  }

  export function hasFile(): boolean {
    return file != null;
  }

  /** Resolves true when stored. On failure the component shows the error and offers retry. */
  export async function start(id: string): Promise<boolean> {
    if (!file) return false;
    controller = new AbortController();
    phase = 'uploading';
    fileError = null;
    loaded = 0;
    total = file.size;
    let bytesSent = false;
    try {
      const result = await uploadPoster(id, file, {
        signal: controller.signal,
        onProgress: (l, t) => {
          loaded = l;
          total = t;
        },
        onProcessing: () => {
          bytesSent = true;
          phase = 'processing';
        },
      });
      phase = 'done';
      file = null;
      setPreview(null);
      input.value = '';
      showToast('Poster saved.');
      onuploaded(result);
      return true;
    } catch (e) {
      phase = 'error';
      if (e instanceof GraphQLRequestError && e.code === 'ABORTED') {
        showToast(
          bytesSent
            ? 'Cancelled. The server had already received the file, so the poster may still have changed.'
            : 'Upload cancelled.',
        );
        if (bytesSent) oncancelled?.();
      } else {
        const refused = e instanceof GraphQLRequestError ? e.fieldErrors.file : undefined;
        if (refused) fileError = sentence(refused);
        else showToast(describeError(e), 'error');
      }
      return false;
    } finally {
      controller = null;
    }
  }

  onDestroy(() => {
    controller?.abort();
    setPreview(null);
  });
</script>

<section class="upload form-width" aria-labelledby="poster-heading">
  <h2 id="poster-heading">Poster</h2>
  <div class="layout">
    <div class="preview">
      {#if previewUrl}
        <img src={previewUrl} alt="Preview of the chosen file" />
      {:else}
        <Poster {artwork} {title} />
      {/if}
      {#if busy}
        <!-- Under the preview: a thin bar and the percentage of bytes sent (D59). -->
        <progress max="100" value={phase === 'processing' ? 100 : percent} aria-label="Upload progress"
        ></progress>
        <p class="meta percent">{phase === 'processing' ? '100%' : `${percent}%`}</p>
      {/if}
    </div>
    <div class="controls">
      <p class="muted">JPEG, PNG or WebP, up to 5 MB. The whole image is kept.</p>
      <label class="button">
        {artwork ? 'Choose a new image' : 'Choose an image'}
        <input
          bind:this={input}
          class="visually-hidden"
          type="file"
          accept={ACCEPTED_TYPES.join(',')}
          onchange={choose}
          disabled={busy}
          aria-invalid={fileError ? 'true' : undefined}
          aria-describedby={fileError ? 'poster-error' : undefined}
        />
      </label>
      {#if fileError}
        <p class="field-error" id="poster-error">{fileError}</p>
      {/if}
      {#if file}
        <p class="meta">{file.name} · {(file.size / 1024).toFixed(0)} KB</p>
      {/if}

      <div aria-live="polite">
        {#if phase === 'uploading'}
          <p>Uploading… {(loaded / 1024).toFixed(0)} of {(total / 1024).toFixed(0)} KB</p>
        {:else if phase === 'processing'}
          <p>Processing… the server is checking and storing the image.</p>
        {/if}
      </div>

      <div class="actions">
        {#if busy}
          <button type="button" onclick={() => controller?.abort()}>Cancel upload</button>
        {:else if file && movieId}
          <button type="button" class="primary" onclick={() => start(movieId)}>
            {phase === 'error' ? 'Retry upload' : 'Upload poster'}
          </button>
        {/if}
        {#if file && !busy}
          <button type="button" onclick={clear}>Remove choice</button>
        {/if}
      </div>
    </div>
  </div>
</section>

<style>
  .layout {
    display: grid;
    grid-template-columns: minmax(7rem, 12rem) 1fr;
    gap: 1rem 1.5rem;
    align-items: start;
  }
  .preview img {
    width: 100%;
    aspect-ratio: 2 / 3;
    object-fit: contain;
    background: var(--line);
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  /* A thin teal bar; the browser's own look is switched off so the colours apply. */
  progress {
    appearance: none;
    display: block;
    width: 100%;
    height: 4px;
    margin-top: 0.75rem;
    border: 0;
    border-radius: 2px;
    background: var(--line);
    overflow: hidden;
  }
  progress::-webkit-progress-bar {
    background: var(--line);
  }
  progress::-webkit-progress-value {
    background: var(--accent);
  }
  progress::-moz-progress-bar {
    background: var(--accent);
  }
  .percent {
    margin: 0.25rem 0 0;
    text-align: center;
  }
  .controls p {
    margin: 0 0 0.5rem;
  }
  .controls .field-error {
    margin-top: 0.5rem;
  }
  .actions {
    margin-top: 0.5rem;
  }
</style>
