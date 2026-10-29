-- R4: image bytes now live in media-service; catalogue keeps only which artwork a movie uses and
-- what the lists need to show it. Earlier rows pointed at files in catalogue's own folder, which
-- no longer exists, so they are dropped with the old table; the demo seeder re-attaches demo posters.
DROP TABLE movie_poster;

CREATE TABLE movie_poster (
    movie_id      uuid PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    -- An ID in media-service's database; no foreign key can cross that boundary.
    artwork_id    uuid NOT NULL UNIQUE,
    width         integer NOT NULL CHECK (width BETWEEN 1 AND 10000),
    height        integer NOT NULL CHECK (height BETWEEN 1 AND 10000),
    full_url      varchar(200) NOT NULL,
    thumbnail_url varchar(200) NOT NULL,
    created_at    timestamptz NOT NULL
);
