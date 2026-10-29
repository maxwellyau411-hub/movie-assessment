/** Small timing helpers for search inputs. Plain functions, so they are unit-tested without a browser. */

export interface Debounced<A extends unknown[]> {
  (...args: A): void;
  /** Drops a pending call, e.g. when the page is left. */
  cancel(): void;
}

/** Runs `fn` only after `waitMs` without another call; only the last call's arguments are used. */
export function debounce<A extends unknown[]>(fn: (...args: A) => void, waitMs: number): Debounced<A> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const debounced = (...args: A) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), waitMs);
  };
  debounced.cancel = () => clearTimeout(timer);
  return debounced;
}

/** The result of a superseded run: the caller should ignore it. */
export const STALE = Symbol('stale');

/**
 * Keeps only the newest request. Each `run` aborts the previous one (so the browser cancels it)
 * and, if an older request still finishes, returns STALE instead of its result. That way a slow
 * answer for "ab" can never overwrite a newer answer for "abc".
 */
export function latestOnly() {
  let current: AbortController | undefined;
  return {
    async run<T>(request: (signal: AbortSignal) => Promise<T>): Promise<T | typeof STALE> {
      current?.abort();
      const controller = new AbortController();
      current = controller;
      try {
        const result = await request(controller.signal);
        return current === controller ? result : STALE;
      } catch (error) {
        if (current !== controller) return STALE;
        throw error;
      }
    },
    cancel() {
      current?.abort();
      current = undefined;
    },
  };
}
