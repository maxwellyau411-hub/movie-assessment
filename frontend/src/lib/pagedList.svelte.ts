import { afterNavigate, beforeNavigate } from '$app/navigation';
import { onDestroy, tick } from 'svelte';
import { latestOnly, STALE } from './async';
import { describeError } from './errors';
import { listCache } from './listCache';
import type { Page, TotalCount } from './types';

export interface PagedListOptions<T> {
  /** Fetches one page for the list described by `url` (its query string holds search, filter and sort). */
  fetchPage: (url: URL, after: string | null, signal: AbortSignal) => Promise<Page<T>>;
  /**
   * Called when the URL changed from outside the page's own inputs (a link, back/forward,
   * refresh), so the inputs can show what the URL now says.
   */
  onExternalNavigation?: (url: URL) => void;
}

/**
 * The shared logic of the three lists (movies, Cast, Creators), driven by the URL:
 *
 * - Every navigation to the page (including the page's own `goto` after a filter change) loads
 *   the first page for the new URL. An older request still in flight is cancelled, so it can
 *   never overwrite the newer result.
 * - "Load more" appends the next page using the last cursor.
 * - Leaving the page remembers what was loaded and the scroll position; coming back with the
 *   browser's back button restores both instead of starting again from the first page (D48).
 *
 * Must be called while a component is being created (it registers navigation callbacks).
 */
export function pagedList<T>(options: PagedListOptions<T>) {
  const latest = latestOnly();
  const list = $state({
    items: [] as T[],
    endCursor: null as string | null,
    hasNextPage: false,
    total: null as TotalCount | null,
    loading: true,
    loadingMore: false,
    error: null as string | null,
    moreError: null as string | null,
  });
  // The URL whose rows are on screen; "Load more" continues that list.
  let shownUrl: URL | null = null;

  const key = (url: URL) => url.pathname + url.search;

  async function loadFirst(url: URL) {
    shownUrl = url;
    Object.assign(list, { loading: true, loadingMore: false, error: null, moreError: null });
    let page: Page<T> | typeof STALE;
    try {
      page = await latest.run((signal) => options.fetchPage(url, null, signal));
    } catch (error) {
      Object.assign(list, { loading: false, error: describeError(error) });
      return;
    }
    if (page === STALE) return;
    Object.assign(list, {
      items: page.nodes,
      endCursor: page.pageInfo.endCursor,
      hasNextPage: page.pageInfo.hasNextPage,
      total: page.totalCount ?? null,
      loading: false,
    });
  }

  async function loadMore() {
    if (!shownUrl || !list.hasNextPage || list.loadingMore) return;
    const url = shownUrl;
    const after = list.endCursor;
    Object.assign(list, { loadingMore: true, moreError: null });
    let page: Page<T> | typeof STALE;
    try {
      page = await latest.run((signal) => options.fetchPage(url, after, signal));
    } catch (error) {
      Object.assign(list, { loadingMore: false, moreError: describeError(error) });
      return;
    }
    if (page === STALE) return;
    Object.assign(list, {
      items: [...list.items, ...page.nodes],
      endCursor: page.pageInfo.endCursor,
      hasNextPage: page.pageInfo.hasNextPage,
      loadingMore: false,
    });
  }

  beforeNavigate(() => {
    if (!shownUrl || list.loading || list.error) return;
    listCache.set(key(shownUrl), {
      items: $state.snapshot(list.items) as T[],
      endCursor: list.endCursor,
      hasNextPage: list.hasNextPage,
      total: list.total,
      scrollY: window.scrollY,
    });
  });

  afterNavigate(async (navigation) => {
    const url = navigation.to?.url ?? new URL(window.location.href);
    if (navigation.type !== 'goto') options.onExternalNavigation?.(url);
    const cached = navigation.type === 'popstate' ? listCache.get<T>(key(url)) : undefined;
    if (!cached) {
      await loadFirst(url);
      return;
    }
    latest.cancel();
    shownUrl = url;
    Object.assign(list, { ...cached, loading: false, loadingMore: false, error: null, moreError: null });
    // Scroll once the restored rows are on the page; SvelteKit's own restore ran before they were.
    await tick();
    window.scrollTo(0, cached.scrollY);
  });

  onDestroy(() => latest.cancel());

  return {
    list,
    loadMore,
    retry: () => {
      if (shownUrl) loadFirst(shownUrl);
    },
  };
}
