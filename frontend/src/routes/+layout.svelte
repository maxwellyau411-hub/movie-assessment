<script lang="ts">
  // Fonts are bundled with the app (@fontsource), so no font server is contacted (D59).
  import '@fontsource/fraunces/400.css';
  import '@fontsource/fraunces/600.css';
  import '@fontsource/ibm-plex-sans/400.css';
  import '@fontsource/ibm-plex-sans/600.css';
  import '@fontsource/ibm-plex-mono/400.css';
  import '../app.css';
  import { page } from '$app/state';
  import Toasts from '$lib/components/Toasts.svelte';

  let { children } = $props();

  const links = [
    { href: '/', label: 'Movies', active: (path: string) => path === '/' || path.startsWith('/movies') },
    { href: '/cast', label: 'Cast', active: (path: string) => path.startsWith('/cast') },
    { href: '/creators', label: 'Creators', active: (path: string) => path.startsWith('/creators') },
  ];
</script>

<a class="skip visually-hidden" href="#main">Skip to content</a>
<header>
  <nav class="container" aria-label="Main">
    <a class="brand" href="/">Movie Catalogue</a>
    <ul>
      {#each links as link (link.href)}
        <li>
          <a href={link.href} aria-current={link.active(page.url.pathname) ? 'page' : undefined}
            >{link.label}</a
          >
        </li>
      {/each}
    </ul>
  </nav>
</header>

<main id="main" class="container">
  {@render children()}
</main>

<footer>
  <div class="container"><a href="/status">System status</a></div>
</footer>

<Toasts />

<style>
  header {
    border-bottom: 1px solid var(--line);
  }
  nav {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.25rem 1rem;
    padding-block: 0.25rem;
  }
  .brand {
    font-family: var(--font-serif);
    font-weight: 600;
    font-size: 1.25rem;
    color: var(--text);
    text-decoration: none;
    padding: 0.5rem 0;
  }
  ul {
    display: flex;
    gap: 0.25rem;
    list-style: none;
    margin: 0;
    padding: 0;
  }
  ul a {
    display: inline-flex;
    align-items: center;
    min-height: 2.75rem;
    padding: 0 0.75rem;
    border-radius: var(--radius);
    color: var(--text);
    text-decoration: none;
  }
  ul a:hover,
  ul a[aria-current='page'] {
    background: var(--tint);
  }
  ul a[aria-current='page'] {
    font-weight: 600;
  }
  .skip:focus {
    position: static;
    width: auto;
    height: auto;
    clip-path: none;
  }
  footer {
    margin-top: 2rem;
    border-top: 1px solid var(--line);
    /* Room for a message at the bottom, so it never hides the last link for long. */
    padding-bottom: 4rem;
  }
  footer a {
    color: var(--muted);
  }
</style>
