-- Baseline for catalogue_db, owned exclusively by catalogue-service.
-- Establishes Flyway history on a fresh database. Movies, people, genres and credits are added by
-- later, append-only migrations from Step 03. Applied migrations are never edited.
COMMENT ON DATABASE catalogue_db IS 'Owned by catalogue-service; no other service may connect.';
