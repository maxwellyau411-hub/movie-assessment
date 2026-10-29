-- Optional large catalogue for performance checks. Not part of the normal start-up.
--
--   docker compose run --rm large-seed
--
-- Sizes are psql variables, set from LARGE_SEED_MOVIES / _PEOPLE / _CREDITS in compose.yaml
-- (defaults 20000 / 10000 / 100000). Runs as catalogue_service against catalogue_db, after
-- catalogue-service has applied its migrations.
--
-- Rules the services enforce are kept here too: every movie has a genre, cast credits only name
-- Cast people, creator credits only name Creator people, birth dates are in the past.
-- Large-seed movies and people get no images.
--
-- IDs are derived from the row number (md5 of a fixed label), and every insert skips existing
-- rows, so running it again with the same sizes adds nothing.

\set ON_ERROR_STOP on

-- psql variables are not visible inside a DO block, so pass the sizes as session settings.
SET seed.movies = :'movies';
SET seed.people = :'people';
SET seed.credits = :'credits';
DO $$
BEGIN
    IF current_setting('seed.movies')::int < 1 OR current_setting('seed.people')::int < 20
       OR current_setting('seed.credits')::int < 0 THEN
        RAISE EXCEPTION 'Sizes must be: movies >= 1, people >= 20, credits >= 0';
    END IF;
END $$;

\echo Seeding :movies movies, :people people, :credits credits...

BEGIN;

-- Same seed each time, so the generated catalogue is reproducible.
SELECT setseed(0.42);

CREATE TEMP TABLE seed_words ON COMMIT DROP AS
SELECT
    ARRAY['Silent', 'Crimson', 'Last', 'Hidden', 'Broken', 'Golden', 'Distant', 'Burning', 'Frozen',
          'Midnight', 'Endless', 'Quiet', 'Wild', 'Lost', 'Electric', 'Hollow', 'Iron', 'Paper',
          'Velvet', 'Northern', 'Secret', 'Fading', 'Bright', 'Savage', 'Gentle', 'Restless',
          'Open', 'Bitter', 'Lonely', 'Shining'] AS adjectives,
    ARRAY['Harbour', 'Garden', 'River', 'Empire', 'Signal', 'Orchard', 'Voyage', 'Station',
          'Kingdom', 'Mirror', 'Season', 'Frontier', 'Lantern', 'Tide', 'Horizon', 'Archive',
          'Promise', 'Circus', 'Valley', 'Machine', 'Letters', 'Bridge', 'Island', 'Summer',
          'Engine', 'Witness', 'Canyon', 'Colony', 'Parade', 'Forest'] AS nouns,
    ARRAY['II', 'III', 'Returns', 'Reborn', 'Forever', 'Rising'] AS suffixes,
    ARRAY['Amara', 'Bruno', 'Chiara', 'Dmitri', 'Elena', 'Farid', 'Greta', 'Hiro', 'Ingrid',
          'Jonas', 'Keiko', 'Lionel', 'Maya', 'Nikolai', 'Olga', 'Pablo', 'Quinn', 'Rosa',
          'Samir', 'Tilda', 'Umar', 'Vera', 'Wim', 'Ximena', 'Yusuf', 'Zora', 'Anton', 'Beatrix',
          'Callum', 'Dalia', 'Emil', 'Freya', 'Gustav', 'Hana', 'Idris', 'Juno', 'Kofi', 'Lena',
          'Marco', 'Nadia'] AS first_names,
    ARRAY['Okafor', 'Lindqvist', 'Moreau', 'Tanaka', 'Novak', 'Castillo', 'Haddad', 'Brennan',
          'Kowalski', 'Ferreira', 'Nakamura', 'Ivanova', 'Osei', 'Larsen', 'Rossi', 'Petrov',
          'Delacroix', 'Mbeki', 'Sorensen', 'Alvarez', 'Fischer', 'Quispe', 'Yilmaz', 'Duarte',
          'Kaur', 'Whitfield', 'Abiodun', 'Varga', 'Holm', 'Santos', 'Achterberg', 'Belov',
          'Carmichael', 'Dragomir', 'Esposito', 'Fontaine', 'Gallo', 'Hartmann', 'Ishikawa',
          'Jovanovic'] AS last_names,
    -- Weighted: common genres appear several times, so they are picked more often.
    ARRAY['DRAMA', 'DRAMA', 'DRAMA', 'DRAMA', 'COMEDY', 'COMEDY', 'COMEDY', 'THRILLER', 'THRILLER',
          'ACTION', 'ACTION', 'ROMANCE', 'ROMANCE', 'CRIME', 'CRIME', 'HORROR', 'ADVENTURE',
          'DOCUMENTARY', 'SCIENCE_FICTION', 'FAMILY', 'FANTASY', 'MYSTERY', 'ANIMATION', 'HISTORY',
          'MUSIC', 'WAR', 'WESTERN'] AS genres;

-- Movies: three title patterns, release years weighted towards recent decades, about 5%
-- unknown dates and a few future ones (allowed, D13).
INSERT INTO movie (id, title, synopsis, release_date, runtime_minutes, version, created_at, updated_at)
SELECT md5('large-seed:movie:' || n)::uuid,
       CASE n % 3
           WHEN 0 THEN 'The ' || adjectives[1 + floor(random() * 30)::int] || ' ' || nouns[1 + floor(random() * 30)::int]
           WHEN 1 THEN adjectives[1 + floor(random() * 30)::int] || ' ' || nouns[1 + floor(random() * 30)::int]
                       || ' ' || suffixes[1 + floor(random() * 6)::int]
           ELSE nouns[1 + floor(random() * 30)::int] || ' of the ' || adjectives[1 + floor(random() * 30)::int]
                || ' ' || nouns[1 + floor(random() * 30)::int]
       END,
       CASE WHEN n % 4 = 0 THEN NULL ELSE 'Generated movie number ' || n || ' for performance testing.' END,
       CASE WHEN n % 20 = 0 THEN NULL
            ELSE make_date(2027 - floor(107 * random() ^ 2)::int, 1, 1) + floor(random() * 365)::int
       END,
       CASE WHEN n % 30 = 0 THEN NULL ELSE 70 + floor(random() * 110)::int END,
       0, now(), now()
FROM generate_series(1, :movies) AS n, seed_words
ON CONFLICT DO NOTHING;

-- One to three genres per movie. A repeated pick is skipped, so the first one always lands.
INSERT INTO movie_genre (movie_id, genre_code)
SELECT md5('large-seed:movie:' || n)::uuid, genres[1 + floor(random() * array_length(genres, 1))::int]
FROM generate_series(1, :movies) AS n
CROSS JOIN LATERAL generate_series(1, 1 + n % 3) AS k, seed_words
ON CONFLICT DO NOTHING;

-- People: 60% Cast only, 25% Creator only, 15% both. About 20% have no birth date.
INSERT INTO person (id, name, date_of_birth, biography, is_cast, is_creator, version, created_at, updated_at)
SELECT md5('large-seed:person:' || n)::uuid,
       first_names[1 + floor(random() * 40)::int]
           || CASE WHEN random() < 0.5 THEN ' ' || chr(65 + floor(random() * 26)::int) || '.' ELSE '' END
           || ' ' || last_names[1 + floor(random() * 40)::int],
       CASE WHEN n % 5 = 0 THEN NULL ELSE date '1930-01-01' + floor(random() * 365 * 80)::int END,
       NULL,
       n % 20 < 12 OR n % 20 >= 17,
       n % 20 >= 12,
       0, now(), now()
FROM generate_series(1, :people) AS n, seed_words
ON CONFLICT DO NOTHING;

-- Numbered lists of the seeded Cast and Creator people, to pick credit people from.
CREATE TEMP TABLE seed_cast ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY n) - 1 AS i, md5('large-seed:person:' || n)::uuid AS id
FROM generate_series(1, :people) AS n WHERE n % 20 < 12 OR n % 20 >= 17;

CREATE TEMP TABLE seed_creator ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY n) - 1 AS i, md5('large-seed:person:' || n)::uuid AS id
FROM generate_series(1, :people) AS n WHERE n % 20 >= 12;

-- Credits: 70% cast, 30% creator, dealt round-robin over the movies. Credit c goes to movie
-- (c mod movies) as that movie's k-th credit (k = c / movies). The person index
-- (movie * prime + k * prime) mod list size spreads people evenly and does not repeat a person
-- within one movie; any rare clash is skipped. bigint keeps the arithmetic from overflowing.
INSERT INTO cast_credit (id, movie_id, person_id, character_name)
SELECT md5('large-seed:cast:' || c)::uuid,
       md5('large-seed:movie:' || (1 + c % :movies))::uuid,
       p.id,
       CASE WHEN c % 2 = 0 THEN NULL ELSE first_names[1 + floor(random() * 40)::int] END
FROM generate_series(0::bigint, (:credits * 7) / 10 - 1) AS c
CROSS JOIN seed_words
JOIN seed_cast p ON p.i = ((1 + c % :movies) * 7919 + (c / :movies) * 104729) % (SELECT count(*) FROM seed_cast)
ON CONFLICT DO NOTHING;

INSERT INTO creator_credit (id, movie_id, person_id, role)
SELECT md5('large-seed:creator:' || c)::uuid,
       md5('large-seed:movie:' || (1 + c % :movies))::uuid,
       p.id,
       -- Each movie's first creator is its director; about 10% of the rest have no role.
       CASE WHEN c / :movies = 0 THEN 'DIRECTOR'
            WHEN c % 10 = 0 THEN NULL
            ELSE (ARRAY['WRITER', 'PRODUCER', 'COMPOSER'])[1 + c % 3]
       END
FROM generate_series(0::bigint, (:credits * 3) / 10 - 1) AS c
JOIN seed_creator p ON p.i = ((1 + c % :movies) * 7907 + (c / :movies) * 104723) % (SELECT count(*) FROM seed_creator)
ON CONFLICT DO NOTHING;

COMMIT;

-- Fresh statistics, so the query planner sees the new table sizes straight away.
ANALYZE movie, movie_genre, person, cast_credit, creator_credit;

SELECT (SELECT count(*) FROM movie) AS movies,
       (SELECT count(*) FROM person) AS people,
       (SELECT count(*) FROM cast_credit) + (SELECT count(*) FROM creator_credit) AS credits;
