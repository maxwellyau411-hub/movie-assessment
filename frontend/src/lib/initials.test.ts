import { describe, expect, it } from 'vitest';
import { initials, PLACEHOLDER_COLOURS, placeholderColour } from './initials';

describe('initials', () => {
  it.each([
    ['The Lantern Keeper', 'LK'],
    ['Ilse Varnholt', 'IV'],
    ['Orbit', 'O'],
    ['The', 'T'],
    ['  paper   harbour  ', 'PH'],
    ['Élodie Marchand', 'ÉM'],
    ['2046', '2'],
    ['“Quoted” Title', 'QT'],
    ['— ', '?'],
  ])('%s → %s', (text, expected) => {
    expect(initials(text)).toBe(expected);
  });
});

describe('placeholderColour', () => {
  it('gives the same name the same colour every time', () => {
    expect(placeholderColour('Ilse Varnholt')).toBe('var(--placeholder-3)');
    expect(placeholderColour('Ilse Varnholt')).toBe(placeholderColour('  Ilse Varnholt '));
    expect(placeholderColour('Paper Harbour')).toBe('var(--placeholder-0)');
  });

  it('only uses the colours app.css defines, and uses all of them', () => {
    const used = new Set(['A', 'B', 'C', 'D', 'E', 'Orbit', ''].map(placeholderColour));
    for (const colour of used) expect(colour).toMatch(/^var\(--placeholder-[0-4]\)$/);
    expect(used.size).toBe(PLACEHOLDER_COLOURS);
  });
});
