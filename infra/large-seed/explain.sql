-- EXPLAIN ANALYZE evidence for the list queries (R3). Run after the large seed:
--
--   docker compose run --rm large-seed psql -f /seed/explain.sql
--
-- Each query is the SQL that MovieQueryService / PersonQueryService send, with literal values
-- in place of the bound parameters, and LIMIT 21 (the default page of 20, plus one row that
-- shows whether a next page exists). Each runs twice: with indexes, then with index and bitmap
-- scans switched off, which forces the plan PostgreSQL would need without the R3 indexes.

\set ON_ERROR_STOP on
\pset pager off

SELECT (SELECT count(*) FROM movie) AS movies, (SELECT count(*) FROM person) AS people,
       (SELECT count(*) FROM cast_credit) + (SELECT count(*) FROM creator_credit) AS credits;

-- A cursor three quarters of the way down the title list, for the deep-page queries.
SELECT title AS deep_title, id AS deep_id, (SELECT count(*) * 3 / 4 FROM movie) AS deep_offset
FROM movie ORDER BY lower(title), id OFFSET (SELECT count(*) * 3 / 4 FROM movie) LIMIT 1 \gset

\set label 'M1. Title search "lantern" (common word), title A-Z, first page'
\set q 'select m.* from movie m where lower(m.title) like ''%lantern%'' order by lower(m.title), m.id limit 21'
\ir explain-one.psql

\set label 'M2. Title search "velvet lan" (rare), title A-Z, first page'
\set q 'select m.* from movie m where lower(m.title) like ''%velvet lan%'' order by lower(m.title), m.id limit 21'
\ir explain-one.psql

\set label 'P1. Cast name search "okafor", name A-Z, first page'
\set q 'select p.* from person p where p.is_cast and lower(p.name) like ''%okafor%'' order by lower(p.name), p.id limit 21'
\ir explain-one.psql

\set label 'M3. Genre WAR + year 2015, title A-Z, first page'
\set q 'select m.* from movie m where exists (select 1 from movie_genre g where g.movie_id = m.id and g.genre_code in (''WAR'')) and coalesce(m.release_date, cast(''infinity'' as date)) between ''2015-01-01'' and ''2015-12-31'' order by lower(m.title), m.id limit 21'
\ir explain-one.psql

\set label 'S1. Sort title A-Z, first page'
\set q 'select m.* from movie m order by lower(m.title), m.id limit 21'
\ir explain-one.psql

\set label 'S2. Sort title Z-A, first page'
\set q 'select m.* from movie m order by lower(m.title) desc, m.id desc limit 21'
\ir explain-one.psql

\set label 'S3. Sort release date newest first, first page'
\set q 'select m.* from movie m order by coalesce(m.release_date, cast(''-infinity'' as date)) desc, m.id desc limit 21'
\ir explain-one.psql

\set label 'S4. Sort release date oldest first, first page'
\set q 'select m.* from movie m order by coalesce(m.release_date, cast(''infinity'' as date)), m.id limit 21'
\ir explain-one.psql

\set label 'S5. Cast list, name A-Z, first page'
\set q 'select p.* from person p where p.is_cast order by lower(p.name), p.id limit 21'
\ir explain-one.psql

\set label 'S6. Creators list, name Z-A, first page'
\set q 'select p.* from person p where p.is_creator order by lower(p.name) desc, p.id desc limit 21'
\ir explain-one.psql

\set label 'S7. Creators with role DIRECTOR, name A-Z, first page'
\set q 'select p.* from person p where p.is_creator and exists (select 1 from creator_credit c where c.person_id = p.id and c.role = ''DIRECTOR'') order by lower(p.name), p.id limit 21'
\ir explain-one.psql

\set label 'D1. Deep page, title A-Z, cursor three quarters down the list (keyset, as the app does)'
\set q 'select m.* from movie m where (lower(m.title), m.id) > (lower(' :'deep_title' '), ' :'deep_id' ') order by lower(m.title), m.id limit 21'
\ir explain-one.psql

\set label 'D2. The same deep page with OFFSET (the old page-number approach), for comparison'
\set q 'select m.* from movie m order by lower(m.title), m.id offset ' :deep_offset ' limit 21'
\ir explain-one.psql

\set label 'C1. totalCount, no filter (capped at 10,000)'
\set q 'select count(*) from (select 1 from movie m limit 10001) matches'
\ir explain-one.psql

\set label 'C2. totalCount, title search "lantern"'
\set q 'select count(*) from (select 1 from movie m where lower(m.title) like ''%lantern%'' limit 10001) matches'
\ir explain-one.psql
