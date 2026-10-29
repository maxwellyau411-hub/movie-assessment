/**
 * List search, filter and sort live in the URL (`?q=…&genre=…&year=…&sort=…`), so back, forward,
 * refresh and shared links all keep them. Reading is forgiving: an unknown or malformed value
 * falls back to the default instead of failing the page.
 */

export const MOVIE_SORTS = ['TITLE_ASC', 'TITLE_DESC', 'RELEASE_DATE_NEWEST', 'RELEASE_DATE_OLDEST'] as const;
export type MovieSort = (typeof MOVIE_SORTS)[number];

export const PERSON_SORTS = ['NAME_ASC', 'NAME_DESC'] as const;
export type PersonSort = (typeof PERSON_SORTS)[number];

export const CREATOR_ROLES = ['DIRECTOR', 'WRITER', 'PRODUCER', 'COMPOSER'] as const;
export type CreatorRole = (typeof CREATOR_ROLES)[number];

export interface MovieListState {
  search: string;
  genres: string[];
  year: number | null;
  sort: MovieSort;
}

export interface PersonListState {
  search: string;
  sort: PersonSort;
  role: CreatorRole | null;
}

function oneOf<T extends string>(value: string | null, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

function year(value: string | null): number | null {
  if (value == null || !/^\d{4}$/.test(value)) return null;
  return Number(value);
}

export function readMovieListState(params: URLSearchParams): MovieListState {
  return {
    search: params.get('q')?.trim() ?? '',
    genres: [...new Set(params.getAll('genre').filter((g) => /^[A-Z_]+$/.test(g)))].sort(),
    year: year(params.get('year')),
    sort: oneOf(params.get('sort'), MOVIE_SORTS, 'TITLE_ASC'),
  };
}

/** Defaults are left out, so the plain list is just `/`. Genres are sorted for a stable URL. */
export function movieListQuery(state: MovieListState): string {
  const params = new URLSearchParams();
  if (state.search.trim()) params.set('q', state.search.trim());
  for (const genre of [...state.genres].sort()) params.append('genre', genre);
  if (state.year != null) params.set('year', String(state.year));
  if (state.sort !== 'TITLE_ASC') params.set('sort', state.sort);
  const query = params.toString();
  return query ? `?${query}` : '';
}

export function readPersonListState(params: URLSearchParams): PersonListState {
  const role = params.get('role');
  return {
    search: params.get('q')?.trim() ?? '',
    sort: oneOf(params.get('sort'), PERSON_SORTS, 'NAME_ASC'),
    role: CREATOR_ROLES.includes(role as CreatorRole) ? (role as CreatorRole) : null,
  };
}

export function personListQuery(state: PersonListState): string {
  const params = new URLSearchParams();
  if (state.search.trim()) params.set('q', state.search.trim());
  if (state.sort !== 'NAME_ASC') params.set('sort', state.sort);
  if (state.role) params.set('role', state.role);
  const query = params.toString();
  return query ? `?${query}` : '';
}
