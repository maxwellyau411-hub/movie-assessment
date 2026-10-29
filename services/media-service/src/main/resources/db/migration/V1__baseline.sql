-- Baseline for media_db, owned exclusively by media-service.
-- Establishes Flyway history on a fresh database. Applied migrations are never edited.
COMMENT ON DATABASE media_db IS 'Owned by media-service; no other service may connect.';
