import type { CreatorRole } from './listState';

export type PersonCategory = 'CAST' | 'CREATOR';

export interface Genre {
  code: string;
  name: string;
}

export interface Artwork {
  thumbnailUrl: string;
  fullUrl: string;
  width: number;
  height: number;
}

export interface TotalCount {
  count: number;
  /** True when counting stopped at 10,000: show "10,000+". */
  capped: boolean;
}

export interface Page<T> {
  nodes: T[];
  pageInfo: { hasNextPage: boolean; endCursor: string | null };
  /** Only requested for the first page; later pages keep the first page's total. */
  totalCount?: TotalCount;
}

export interface MovieSummary {
  id: string;
  title: string;
  releaseYear: number | null;
  genres: Genre[];
  artwork: Artwork | null;
}

export interface MovieRef {
  id: string;
  title: string;
  releaseYear: number | null;
}

export interface PersonRef {
  id: string;
  name: string;
  categories: PersonCategory[];
}

export interface CastCredit {
  id: string;
  characterName: string | null;
  movie: MovieRef;
  person: PersonRef;
}

export interface CreatorCredit {
  id: string;
  role: CreatorRole | null;
  movie: MovieRef;
  person: PersonRef;
}

export interface Movie {
  id: string;
  title: string;
  synopsis: string | null;
  releaseDate: string | null;
  releaseYear: number | null;
  runtimeMinutes: number | null;
  genres: Genre[];
  artwork: Artwork | null;
  cast: CastCredit[];
  creators: CreatorCredit[];
  version: number;
}

export interface PersonSummary {
  id: string;
  name: string;
  age: number | null;
  categories: PersonCategory[];
}

export interface Person {
  id: string;
  name: string;
  dateOfBirth: string | null;
  age: number | null;
  biography: string | null;
  categories: PersonCategory[];
  castCredits: CastCredit[];
  creatorCredits: CreatorCredit[];
  version: number;
}

export interface MovieInput {
  title: string;
  synopsis: string | null;
  releaseDate: string | null;
  runtimeMinutes: number | null;
  genreCodes: string[];
}

export interface PersonInput {
  name: string;
  dateOfBirth: string | null;
  biography: string | null;
  categories: PersonCategory[];
}

/** A save either happened, or nothing was saved because similar records exist. */
export type SaveResult<T, R> =
  { kind: 'saved'; value: T } | { kind: 'duplicates'; message: string; candidates: R[] };
