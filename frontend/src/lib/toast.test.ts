import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dismissToast, showToast, toasts } from './toast.svelte';

describe('toasts', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    toasts.splice(0);
    vi.useRealTimers();
  });

  it('hides a message after 5 seconds', () => {
    showToast('Changes saved.');
    expect(toasts.map((t) => t.message)).toEqual(['Changes saved.']);
    vi.advanceTimersByTime(4999);
    expect(toasts).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(toasts).toHaveLength(0);
  });

  it('keeps an error until it is dismissed', () => {
    showToast('Media Service is not reachable.', 'error');
    vi.advanceTimersByTime(60_000);
    expect(toasts).toHaveLength(1);
    dismissToast(toasts[0].id);
    expect(toasts).toHaveLength(0);
  });

  it('shows the same message once', () => {
    showToast('Please correct the highlighted fields.', 'error');
    showToast('Please correct the highlighted fields.', 'error');
    expect(toasts).toHaveLength(1);
  });

  it('shows at most three, dropping the oldest', () => {
    for (const n of [1, 2, 3, 4]) showToast(`Message ${n}`, 'error');
    expect(toasts.map((t) => t.message)).toEqual(['Message 2', 'Message 3', 'Message 4']);
  });
});
