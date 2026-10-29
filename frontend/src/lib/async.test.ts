import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { debounce, latestOnly, STALE } from './async';

describe('debounce', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('runs once, 300 ms after the last call, with the last arguments', () => {
    const calls: string[] = [];
    const search = debounce((text: string) => calls.push(text), 300);

    search('a');
    vi.advanceTimersByTime(200);
    search('ab');
    vi.advanceTimersByTime(200);
    search('abc');
    vi.advanceTimersByTime(299);
    expect(calls).toEqual([]);

    vi.advanceTimersByTime(1);
    expect(calls).toEqual(['abc']);
  });

  it('cancel drops the pending call', () => {
    const fn = vi.fn();
    const debounced = debounce(fn, 300);

    debounced();
    debounced.cancel();
    vi.advanceTimersByTime(1000);

    expect(fn).not.toHaveBeenCalled();
  });
});

/** A request the test resolves by hand, to control which answer arrives first. */
function manual<T>() {
  let resolve!: (value: T) => void;
  let signal!: AbortSignal;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve, run: (s: AbortSignal) => ((signal = s), promise), signal: () => signal };
}

describe('latestOnly', () => {
  it('a slow older answer never replaces a newer one, and the older request is aborted', async () => {
    const latest = latestOnly();
    const older = manual<string>();
    const newer = manual<string>();

    const first = latest.run(older.run);
    const second = latest.run(newer.run);
    expect(older.signal().aborted).toBe(true);
    expect(newer.signal().aborted).toBe(false);

    newer.resolve('abc results');
    older.resolve('ab results');

    expect(await second).toBe('abc results');
    expect(await first).toBe(STALE);
  });

  it('an error from a superseded request is ignored, but the newest error is thrown', async () => {
    const latest = latestOnly();
    const first = latest.run(() => Promise.reject(new Error('old failure')));
    const second = latest.run(() => Promise.reject(new Error('new failure')));

    expect(await first).toBe(STALE);
    await expect(second).rejects.toThrow('new failure');
  });

  it('cancel aborts the request in flight and marks it stale', async () => {
    const latest = latestOnly();
    const request = manual<string>();
    const result = latest.run(request.run);

    latest.cancel();
    request.resolve('late');

    expect(request.signal().aborted).toBe(true);
    expect(await result).toBe(STALE);
  });
});
