/**
 * Short messages at the bottom of the screen (D59): "Changes saved.", "“X” was deleted.", errors.
 * The layout shows them and stays mounted across navigations, so a page can show a message and
 * then go to another page. Messages go after 5 seconds; errors stay until dismissed.
 */
export type ToastKind = 'info' | 'error';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

const SHOWN_FOR_MS = 5000;
const MAX_SHOWN = 3;

export const toasts: Toast[] = $state([]);
let nextId = 1;

export function showToast(message: string, kind: ToastKind = 'info') {
  // The same message again (Save pressed twice) replaces the earlier one instead of stacking.
  const same = toasts.findIndex((toast) => toast.message === message && toast.kind === kind);
  if (same >= 0) toasts.splice(same, 1);
  const id = nextId++;
  toasts.push({ id, kind, message });
  if (toasts.length > MAX_SHOWN) toasts.splice(0, toasts.length - MAX_SHOWN);
  if (kind === 'info') setTimeout(() => dismissToast(id), SHOWN_FOR_MS);
}

export function dismissToast(id: number) {
  const index = toasts.findIndex((toast) => toast.id === id);
  if (index >= 0) toasts.splice(index, 1);
}
