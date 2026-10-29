<script lang="ts">
  import { initials, placeholderColour } from '$lib/initials';
  import type { Artwork } from '$lib/types';

  /** `thumbnail` for lists (lazy, 320 px file), `full` for the detail page. */
  let {
    artwork,
    title,
    size = 'thumbnail',
  }: { artwork: Artwork | null; title: string; size?: 'thumbnail' | 'full' } = $props();
</script>

<div class="poster">
  {#if artwork}
    <img
      src={size === 'full' ? artwork.fullUrl : artwork.thumbnailUrl}
      alt="Poster: {title}"
      width={artwork.width}
      height={artwork.height}
      loading={size === 'full' ? 'eager' : 'lazy'}
      decoding="async"
    />
  {:else}
    <!-- No artwork: the title's initials on a soft colour that always matches the title (D59). -->
    <div
      class="placeholder"
      class:large={size === 'full'}
      style:background={placeholderColour(title)}
      role="img"
      aria-label="No poster for {title}"
    >
      <span aria-hidden="true">{initials(title)}</span>
    </div>
  {/if}
</div>

<style>
  .poster {
    aspect-ratio: 2 / 3;
    width: 100%;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--line);
  }
  img {
    width: 100%;
    height: 100%;
    /* The whole image stays visible (D49); a non-poster shape gets plain bars. */
    object-fit: contain;
  }
  .placeholder {
    display: grid;
    place-items: center;
    width: 100%;
    height: 100%;
    font-family: var(--font-serif);
    font-weight: 400;
    font-size: 2.5rem;
    letter-spacing: 0.04em;
  }
  .placeholder.large {
    font-size: 4.5rem;
  }
</style>
