<script lang="ts">
  import { initials, placeholderColour } from '$lib/initials';

  /** People have no photos: a circle with their initials, in a colour that always matches the name. */
  let { name, size = 2.75 }: { name: string; size?: number } = $props();
</script>

<span class="avatar" style:--size="{size}rem" style:background={placeholderColour(name)} aria-hidden="true"
  >{initials(name)}</span
>

<style>
  .avatar {
    display: inline-grid;
    place-items: center;
    flex: none;
    width: var(--size);
    height: var(--size);
    border-radius: 50%;
    color: var(--text);
    font-family: var(--font-serif);
    font-weight: 600;
    font-size: calc(var(--size) * 0.38);
  }
</style>
