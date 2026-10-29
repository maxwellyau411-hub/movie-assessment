const ARTICLES = new Set(['the', 'a', 'an']);

/**
 * Up to two letters for a placeholder: "The Lantern Keeper" → "LK", "Ilse Varnholt" → "IV",
 * "Orbit" → "O". A leading article is skipped when other words follow.
 */
export function initials(text: string): string {
  let words = text
    .trim()
    .split(/\s+/)
    .filter((w) => /[\p{L}\p{N}]/u.test(w));
  if (words.length > 1 && ARTICLES.has(words[0].toLowerCase())) words = words.slice(1);
  const letters = words.slice(0, 2).map((w) => w.match(/[\p{L}\p{N}]/u)![0].toUpperCase());
  return letters.join('') || '?';
}

/** How many `--placeholder-N` colours app.css defines. */
export const PLACEHOLDER_COLOURS = 5;

/**
 * A soft background for a placeholder, from a hash of the text, so the same movie or person
 * always gets the same colour: "Ilse Varnholt" → "var(--placeholder-3)" on every screen.
 */
export function placeholderColour(text: string): string {
  let hash = 0;
  // Multiply-by-31 string hash; `>>> 0` keeps it a non-negative 32-bit number.
  for (const char of text.trim()) hash = (hash * 31 + char.codePointAt(0)!) >>> 0;
  return `var(--placeholder-${hash % PLACEHOLDER_COLOURS})`;
}
