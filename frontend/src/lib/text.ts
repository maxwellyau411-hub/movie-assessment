/** Server field messages are fragments ("must be between 1 and 1440"); start them with a capital. */
export function sentence(message: string): string {
  const text = message.charAt(0).toUpperCase() + message.slice(1);
  return /[.!?]$/.test(text) ? text : `${text}.`;
}

/** Blank optional text is sent as null, which the server stores as "not given". */
export function optional(value: string): string | null {
  return value.trim() === '' ? null : value;
}
