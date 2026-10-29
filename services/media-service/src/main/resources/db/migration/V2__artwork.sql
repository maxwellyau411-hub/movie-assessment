-- One row per stored image. The bytes are files named after the ID (originals/<id>.<ext> and
-- thumbnails/<id>.jpg); the row only describes them. Limits match ImageValidator.
CREATE TABLE artwork (
    id               uuid PRIMARY KEY,
    content_type     varchar(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    -- As given by the uploader, for reference only; never part of a path.
    file_name        varchar(255),
    width            integer NOT NULL CHECK (width BETWEEN 1 AND 10000),
    height           integer NOT NULL CHECK (height BETWEEN 1 AND 10000),
    byte_size        integer NOT NULL CHECK (byte_size BETWEEN 1 AND 5242880),
    thumbnail_width  integer NOT NULL CHECK (thumbnail_width BETWEEN 1 AND 320),
    thumbnail_height integer NOT NULL CHECK (thumbnail_height BETWEEN 1 AND 960),
    created_at       timestamptz NOT NULL
);
