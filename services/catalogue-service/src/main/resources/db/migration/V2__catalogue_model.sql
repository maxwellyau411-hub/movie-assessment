-- Catalogue model (Chapter 03). Rules the database can hold are enforced here as well as in the
-- application services. Rules needing "today" or cross-table state (birth date not in the future,
-- at least one genre per movie, credit requires the person category) live in the services.

-- Controlled genre list; codes are stable identifiers, names are display labels.
CREATE TABLE genre (
    code varchar(40) PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z_]*$'),
    name varchar(60) NOT NULL UNIQUE
);

INSERT INTO genre (code, name) VALUES
    ('ACTION', 'Action'),
    ('ADVENTURE', 'Adventure'),
    ('ANIMATION', 'Animation'),
    ('COMEDY', 'Comedy'),
    ('CRIME', 'Crime'),
    ('DOCUMENTARY', 'Documentary'),
    ('DRAMA', 'Drama'),
    ('FAMILY', 'Family'),
    ('FANTASY', 'Fantasy'),
    ('HISTORY', 'History'),
    ('HORROR', 'Horror'),
    ('MUSIC', 'Music'),
    ('MYSTERY', 'Mystery'),
    ('ROMANCE', 'Romance'),
    ('SCIENCE_FICTION', 'Science Fiction'),
    ('THRILLER', 'Thriller'),
    ('WAR', 'War'),
    ('WESTERN', 'Western');

-- No unique constraint on title/date: duplicate catalogue records are allowed after a warning (D07).
-- Release year is derived from release_date; there is no separate year column.
CREATE TABLE movie (
    id              uuid PRIMARY KEY,
    title           varchar(200) NOT NULL CHECK (title = btrim(title) AND title <> ''),
    synopsis        varchar(5000) CHECK (synopsis = btrim(synopsis) AND synopsis <> ''),
    release_date    date,
    runtime_minutes integer CHECK (runtime_minutes BETWEEN 1 AND 1440),
    version         bigint NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE TABLE movie_genre (
    movie_id   uuid NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    genre_code varchar(40) NOT NULL REFERENCES genre (code),
    PRIMARY KEY (movie_id, genre_code)
);
CREATE INDEX movie_genre_genre_idx ON movie_genre (genre_code);

-- One shared record per person. The category flags let a person appear in the Cast and/or
-- Creators lists before they have any credit; they are not the creator job role.
CREATE TABLE person (
    id            uuid PRIMARY KEY,
    name          varchar(200) NOT NULL CHECK (name = btrim(name) AND name <> ''),
    date_of_birth date,
    biography     varchar(5000) CHECK (biography = btrim(biography) AND biography <> ''),
    is_cast       boolean NOT NULL,
    is_creator    boolean NOT NULL,
    version       bigint NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT person_has_category CHECK (is_cast OR is_creator)
);

-- No billing order. Deleting a movie removes its credits; a person with credits cannot be deleted.
CREATE TABLE cast_credit (
    id             uuid PRIMARY KEY,
    movie_id       uuid NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    person_id      uuid NOT NULL REFERENCES person (id) ON DELETE RESTRICT,
    character_name varchar(200) CHECK (character_name = btrim(character_name) AND character_name <> ''),
    CONSTRAINT cast_credit_once_per_movie UNIQUE (movie_id, person_id)
);
CREATE INDEX cast_credit_person_idx ON cast_credit (person_id);

-- Role is optional (NULL = "Unspecified"). NULLS NOT DISTINCT (PostgreSQL 15+) also blocks an exact
-- duplicate of a role-less credit; different roles for the same person and movie are allowed.
CREATE TABLE creator_credit (
    id        uuid PRIMARY KEY,
    movie_id  uuid NOT NULL REFERENCES movie (id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES person (id) ON DELETE RESTRICT,
    role      varchar(20) CHECK (role IN ('DIRECTOR', 'WRITER', 'PRODUCER', 'COMPOSER')),
    CONSTRAINT creator_credit_unique UNIQUE NULLS NOT DISTINCT (movie_id, person_id, role)
);
CREATE INDEX creator_credit_person_idx ON creator_credit (person_id);

-- Metadata for a movie's current poster; the image bytes live in artwork storage, never in rows.
CREATE TABLE movie_poster (
    movie_id     uuid PRIMARY KEY REFERENCES movie (id) ON DELETE CASCADE,
    storage_key  varchar(100) NOT NULL UNIQUE CHECK (storage_key ~ '^[a-z0-9][a-z0-9-]*\.(png|jpg|webp)$'),
    content_type varchar(20) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    width        integer NOT NULL CHECK (width BETWEEN 1 AND 10000),
    height       integer NOT NULL CHECK (height BETWEEN 1 AND 10000),
    byte_size    integer NOT NULL CHECK (byte_size BETWEEN 1 AND 5242880),
    created_at   timestamptz NOT NULL
);
