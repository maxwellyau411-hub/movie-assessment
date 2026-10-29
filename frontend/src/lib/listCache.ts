import type { TotalCount } from './types';

/** What a list page had loaded when the user left it, and how far they had scrolled. */
export interface CachedList<T> {
  items: T[];
  endCursor: string | null;
  hasNextPage: boolean;
  total: TotalCount | null;
  scrollY: number;
}

/**
 * Lets the browser's back button return to a list exactly as it was (D48): every "Load more"
 * page and the scroll position. Kept in memory only, keyed by path and query string, so a
 * refresh starts from the first page again. Any save or delete clears it, so a list is never
 * shown with outdated rows.
 */
const cache = new Map<string, CachedList<unknown>>();

export const listCache = {
  get<T>(key: string): CachedList<T> | undefined {
    return cache.get(key) as CachedList<T> | undefined;
  },
  set<T>(key: string, value: CachedList<T>) {
    cache.set(key, value);
  },
  clear() {
    cache.clear();
  },
};
