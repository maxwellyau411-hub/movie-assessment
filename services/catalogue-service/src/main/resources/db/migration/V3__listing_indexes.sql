-- Indexes for listing at scale (R3). Every list query is written to match one of these exactly:
-- the expressions here (lower(...), coalesce(...)) must stay identical to the SQL in
-- MovieQueryService and PersonQueryService, or PostgreSQL cannot use them.

-- Trigram indexes let "contains" search (lower(title) LIKE '%term%') use an index instead of
-- reading every row. pg_trgm is a trusted extension, so the database owner may create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX movie_title_trgm_idx ON movie USING gin (lower(title) gin_trgm_ops);
CREATE INDEX person_name_trgm_idx ON person USING gin (lower(name) gin_trgm_ops);

-- Sort indexes, each ending in id (the tie-breaker). A descending sort reads the same index
-- backwards, which is why descending sorts also order the id descending.
CREATE INDEX movie_title_sort_idx ON movie (lower(title), id);

-- Unknown release dates sort last in both directions. Treating an unknown date as +infinity
-- (oldest first) or -infinity (newest first, read backwards) gives that order from a plain
-- index, with no NULL handling in the cursor comparison.
CREATE INDEX movie_released_oldest_idx ON movie (coalesce(release_date, 'infinity'::date), id);
CREATE INDEX movie_released_newest_idx ON movie (coalesce(release_date, '-infinity'::date), id);

-- The Cast and Creators lists only ever read their own category, so each gets a partial index.
CREATE INDEX person_cast_name_idx ON person (lower(name), id) WHERE is_cast;
CREATE INDEX person_creator_name_idx ON person (lower(name), id) WHERE is_creator;

-- Genre filter: find the movies of a genre without visiting the movie table. Replaces the
-- single-column index; the primary key (movie_id, genre_code) still serves "genres of a movie".
DROP INDEX movie_genre_genre_idx;
CREATE INDEX movie_genre_genre_movie_idx ON movie_genre (genre_code, movie_id);
