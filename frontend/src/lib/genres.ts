import { fetchGenres } from './api';
import type { Genre } from './types';

let cached: Promise<Genre[]> | undefined;

/** The genre list is fixed by a migration, so it is fetched once per visit and shared. */
export function genres(): Promise<Genre[]> {
  cached ??= fetchGenres().catch((error) => {
    cached = undefined; // let a later screen try again
    throw error;
  });
  return cached;
}
