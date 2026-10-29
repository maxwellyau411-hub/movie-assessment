/**
 * Every GraphQL operation the screens use, with typed results. Mutations clear the list cache,
 * so returning to a list after a change always shows fresh rows.
 */
import { graphqlRequest } from './graphql';
import { listCache } from './listCache';
import type { CreatorRole, MovieListState, PersonListState } from './listState';
import type {
  CastCredit,
  CreatorCredit,
  Genre,
  Movie,
  MovieInput,
  MovieRef,
  MovieSummary,
  Page,
  Person,
  PersonCategory,
  PersonInput,
  PersonRef,
  PersonSummary,
  SaveResult,
} from './types';

export const PAGE_SIZE = 20;

// IDs are UUIDs; anything else in a URL simply does not exist (no request needed).
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const ARTWORK = 'artwork { thumbnailUrl fullUrl width height }';
const MOVIE_REF = 'id title releaseYear';
const PERSON_REF = 'id name categories';
const MOVIE_FIELDS = `id title synopsis releaseDate releaseYear runtimeMinutes version genres { code name } ${ARTWORK}
  cast { id characterName movie { ${MOVIE_REF} } person { ${PERSON_REF} } }
  creators { id role movie { ${MOVIE_REF} } person { ${PERSON_REF} } }`;
const PERSON_FIELDS = `id name dateOfBirth age biography categories version
  castCredits { id characterName movie { ${MOVIE_REF} } person { ${PERSON_REF} } }
  creatorCredits { id role movie { ${MOVIE_REF} } person { ${PERSON_REF} } }`;
const PAGE_INFO = 'pageInfo { hasNextPage endCursor }';

// The total is a separate count on the server, so only the first page asks for it.
const total = (after: string | null) => (after ? '' : 'totalCount { count capped }');

export async function fetchGenres(): Promise<Genre[]> {
  return (await graphqlRequest<{ genres: Genre[] }>('{ genres { code name } }')).genres;
}

export async function fetchMovies(
  state: MovieListState,
  after: string | null,
  signal?: AbortSignal,
): Promise<Page<MovieSummary>> {
  const filter = {
    search: state.search || null,
    genreCodes: state.genres.length ? state.genres : null,
    releaseYear: state.year,
  };
  const data = await graphqlRequest<{ movies: Page<MovieSummary> }>(
    `query Movies($first: Int, $after: String, $sort: MovieSort, $filter: MovieFilter) {
      movies(first: $first, after: $after, sort: $sort, filter: $filter) {
        nodes { id title releaseYear genres { code name } ${ARTWORK} } ${PAGE_INFO} ${total(after)}
      }
    }`,
    { first: PAGE_SIZE, after, sort: state.sort, filter },
    { signal },
  );
  return data.movies;
}

export async function fetchMovie(id: string): Promise<Movie | null> {
  if (!UUID.test(id)) return null;
  return (
    await graphqlRequest<{ movie: Movie | null }>(
      `query Movie($id: ID!) { movie(id: $id) { ${MOVIE_FIELDS} } }`,
      { id },
    )
  ).movie;
}

interface MovieSaveResponse {
  __typename: 'MovieSaved' | 'DuplicateMovieWarning';
  movie?: Movie;
  message?: string;
  candidates?: MovieRef[];
}

export async function saveMovie(
  id: string | null,
  expectedVersion: number | null,
  input: MovieInput,
  confirmDuplicate = false,
): Promise<SaveResult<Movie, MovieRef>> {
  const selection = `__typename ... on MovieSaved { movie { ${MOVIE_FIELDS} } }
    ... on DuplicateMovieWarning { message candidates { ${MOVIE_REF} } }`;
  const result = id
    ? (
        await graphqlRequest<{ updateMovie: MovieSaveResponse }>(
          `mutation UpdateMovie($id: ID!, $version: Int!, $input: MovieInput!, $confirm: Boolean) {
            updateMovie(id: $id, expectedVersion: $version, input: $input, confirmDuplicate: $confirm) { ${selection} }
          }`,
          { id, version: expectedVersion, input, confirm: confirmDuplicate },
        )
      ).updateMovie
    : (
        await graphqlRequest<{ createMovie: MovieSaveResponse }>(
          `mutation CreateMovie($input: MovieInput!, $confirm: Boolean) {
            createMovie(input: $input, confirmDuplicate: $confirm) { ${selection} }
          }`,
          { input, confirm: confirmDuplicate },
        )
      ).createMovie;
  listCache.clear();
  return result.__typename === 'MovieSaved'
    ? { kind: 'saved', value: result.movie! }
    : { kind: 'duplicates', message: result.message!, candidates: result.candidates! };
}

export async function deleteMovie(id: string, expectedVersion: number): Promise<void> {
  await graphqlRequest(
    'mutation DeleteMovie($id: ID!, $v: Int!) { deleteMovie(id: $id, expectedVersion: $v) }',
    {
      id,
      v: expectedVersion,
    },
  );
  listCache.clear();
}

export async function fetchPeople(
  category: PersonCategory,
  state: PersonListState,
  after: string | null,
  signal?: AbortSignal,
  first = PAGE_SIZE,
): Promise<Page<PersonSummary>> {
  const field = category === 'CAST' ? 'castMembers' : 'creators';
  const role = category === 'CREATOR' ? ', $role: CreatorRole' : '';
  const roleArg = category === 'CREATOR' ? ', role: $role' : '';
  const data = await graphqlRequest<Record<string, Page<PersonSummary>>>(
    `query People($first: Int, $after: String, $sort: PersonSort, $search: String${role}) {
      ${field}(first: $first, after: $after, sort: $sort, search: $search${roleArg}) {
        nodes { id name age categories } ${PAGE_INFO} ${total(after)}
      }
    }`,
    {
      first,
      after,
      sort: state.sort,
      search: state.search || null,
      ...(category === 'CREATOR' ? { role: state.role } : {}),
    },
    { signal },
  );
  return data[field];
}

export async function fetchPerson(id: string): Promise<Person | null> {
  if (!UUID.test(id)) return null;
  return (
    await graphqlRequest<{ person: Person | null }>(
      `query Person($id: ID!) { person(id: $id) { ${PERSON_FIELDS} } }`,
      {
        id,
      },
    )
  ).person;
}

interface PersonSaveResponse {
  __typename: 'PersonSaved' | 'DuplicatePersonWarning';
  person?: Person;
  message?: string;
  candidates?: PersonRef[];
}

export async function savePerson(
  id: string | null,
  expectedVersion: number | null,
  input: PersonInput,
  confirmDuplicate = false,
): Promise<SaveResult<Person, PersonRef>> {
  const selection = `__typename ... on PersonSaved { person { ${PERSON_FIELDS} } }
    ... on DuplicatePersonWarning { message candidates { ${PERSON_REF} } }`;
  const result = id
    ? (
        await graphqlRequest<{ updatePerson: PersonSaveResponse }>(
          `mutation UpdatePerson($id: ID!, $version: Int!, $input: PersonInput!, $confirm: Boolean) {
            updatePerson(id: $id, expectedVersion: $version, input: $input, confirmDuplicate: $confirm) { ${selection} }
          }`,
          { id, version: expectedVersion, input, confirm: confirmDuplicate },
        )
      ).updatePerson
    : (
        await graphqlRequest<{ createPerson: PersonSaveResponse }>(
          `mutation CreatePerson($input: PersonInput!, $confirm: Boolean) {
            createPerson(input: $input, confirmDuplicate: $confirm) { ${selection} }
          }`,
          { input, confirm: confirmDuplicate },
        )
      ).createPerson;
  listCache.clear();
  return result.__typename === 'PersonSaved'
    ? { kind: 'saved', value: result.person! }
    : { kind: 'duplicates', message: result.message!, candidates: result.candidates! };
}

export async function deletePerson(id: string, expectedVersion: number): Promise<void> {
  await graphqlRequest(
    'mutation DeletePerson($id: ID!, $v: Int!) { deletePerson(id: $id, expectedVersion: $v) }',
    {
      id,
      v: expectedVersion,
    },
  );
  listCache.clear();
}

export async function addCastCredit(
  movieId: string,
  personId: string,
  characterName: string | null,
): Promise<CastCredit> {
  const data = await graphqlRequest<{ addCastCredit: CastCredit }>(
    `mutation AddCast($m: ID!, $p: ID!, $c: String) {
      addCastCredit(movieId: $m, personId: $p, characterName: $c) { id characterName movie { ${MOVIE_REF} } person { ${PERSON_REF} } }
    }`,
    { m: movieId, p: personId, c: characterName },
  );
  listCache.clear();
  return data.addCastCredit;
}

export async function removeCastCredit(creditId: string): Promise<void> {
  await graphqlRequest('mutation RemoveCast($id: ID!) { removeCastCredit(creditId: $id) }', { id: creditId });
  listCache.clear();
}

export async function addCreatorCredit(
  movieId: string,
  personId: string,
  role: CreatorRole | null,
): Promise<CreatorCredit> {
  const data = await graphqlRequest<{ addCreatorCredit: CreatorCredit }>(
    `mutation AddCreator($m: ID!, $p: ID!, $r: CreatorRole) {
      addCreatorCredit(movieId: $m, personId: $p, role: $r) { id role movie { ${MOVIE_REF} } person { ${PERSON_REF} } }
    }`,
    { m: movieId, p: personId, r: role },
  );
  listCache.clear();
  return data.addCreatorCredit;
}

export async function removeCreatorCredit(creditId: string): Promise<void> {
  await graphqlRequest('mutation RemoveCreator($id: ID!) { removeCreatorCredit(creditId: $id) }', {
    id: creditId,
  });
  listCache.clear();
}

export interface ServiceStatus {
  catalogue: 'UP' | 'DOWN';
  mediaService: 'UP' | 'DOWN';
}

export async function fetchServiceStatus(): Promise<ServiceStatus> {
  return (
    await graphqlRequest<{ serviceStatus: ServiceStatus }>(
      'query Status { serviceStatus { catalogue mediaService } }',
    )
  ).serviceStatus;
}
