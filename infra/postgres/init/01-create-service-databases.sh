#!/bin/sh
# Runs once, on an empty data volume (postgres image entrypoint). Creates one owner role and one
# database per service. CONNECT is revoked from PUBLIC, so each service can reach only its own database.
set -eu
: "${MEDIA_DB_PASSWORD:?MEDIA_DB_PASSWORD is required}"
: "${CATALOGUE_DB_PASSWORD:?CATALOGUE_DB_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v media_pw="$MEDIA_DB_PASSWORD" -v catalogue_pw="$CATALOGUE_DB_PASSWORD" <<'SQL'
CREATE ROLE media_service LOGIN PASSWORD :'media_pw';
CREATE ROLE catalogue_service LOGIN PASSWORD :'catalogue_pw';

CREATE DATABASE media_db OWNER media_service;
CREATE DATABASE catalogue_db OWNER catalogue_service;

REVOKE ALL ON DATABASE media_db FROM PUBLIC;
REVOKE ALL ON DATABASE catalogue_db FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
SQL
