#!/usr/bin/env bash
# Verifies database ownership in the running Compose stack: each service role can connect to its own
# database and is refused by the other service's database. Usage: infra/postgres/check-isolation.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source .env; set +a

pass=0; fail=0
try() { # try <role> <password> <database>
  docker compose exec -T -e PGPASSWORD="$2" postgres \
    psql -h 127.0.0.1 -U "$1" -d "$3" -tAc 'select current_user' >/dev/null 2>&1
}
expect() { # expect <ok|denied> <role> <password> <database>
  if try "$2" "$3" "$4"; then got=ok; else got=denied; fi
  if [[ "$got" == "$1" ]]; then pass=$((pass + 1)); echo "PASS  $2 -> $4: $got"
  else fail=$((fail + 1)); echo "FAIL  $2 -> $4: expected $1, got $got"; fi
}

expect ok     media_service     "$MEDIA_DB_PASSWORD"     media_db
expect ok     catalogue_service "$CATALOGUE_DB_PASSWORD" catalogue_db
expect denied media_service     "$MEDIA_DB_PASSWORD"     catalogue_db
expect denied catalogue_service "$CATALOGUE_DB_PASSWORD" media_db
expect denied media_service     "$MEDIA_DB_PASSWORD"     postgres
expect denied catalogue_service "$CATALOGUE_DB_PASSWORD" postgres

echo "RESULT: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
