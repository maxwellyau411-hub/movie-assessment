import { describe, expect, it } from 'vitest';
import { movieListQuery, personListQuery, readMovieListState, readPersonListState } from './listState';

const read = (query: string) => readMovieListState(new URLSearchParams(query));

describe('movie list URL state', () => {
  it('defaults to every movie, title A–Z, and writes no query string for that', () => {
    expect(read('')).toEqual({ search: '', genres: [], year: null, sort: 'TITLE_ASC' });
    expect(movieListQuery(read(''))).toBe('');
  });

  it('round-trips search, genres, year and sort', () => {
    const state = {
      search: 'paper harbour',
      genres: ['WAR', 'DRAMA'],
      year: 2021,
      sort: 'RELEASE_DATE_NEWEST' as const,
    };

    const query = movieListQuery(state);

    expect(query).toBe('?q=paper+harbour&genre=DRAMA&genre=WAR&year=2021&sort=RELEASE_DATE_NEWEST');
    expect(read(query.slice(1))).toEqual({ ...state, genres: ['DRAMA', 'WAR'] });
  });

  it('ignores malformed values instead of failing', () => {
    expect(read('sort=SIDEWAYS&year=20x1&genre=drama;drop&genre=WAR&genre=WAR&q=%20%20')).toEqual({
      search: '',
      genres: ['WAR'],
      year: null,
      sort: 'TITLE_ASC',
    });
    expect(read('year=99999').year).toBeNull();
  });
});

describe('people list URL state', () => {
  it('round-trips search, sort and role, and ignores an unknown role', () => {
    const state = { search: 'ilse', sort: 'NAME_DESC' as const, role: 'DIRECTOR' as const };

    expect(personListQuery(state)).toBe('?q=ilse&sort=NAME_DESC&role=DIRECTOR');
    expect(readPersonListState(new URLSearchParams('q=ilse&sort=NAME_DESC&role=DIRECTOR'))).toEqual(state);
    expect(readPersonListState(new URLSearchParams('role=JUGGLER')).role).toBeNull();
    expect(personListQuery(readPersonListState(new URLSearchParams()))).toBe('');
  });
});
