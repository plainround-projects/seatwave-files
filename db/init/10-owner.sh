#!/usr/bin/env bash
# Runs once inside the postgres container, as the superuser, when the data volume is new.
#   seatwave_files  owns the schema seatwave and the table stored_objects; the lab's programs log in
#                   as this role, never as the superuser
# The password comes from a Compose secret, as a psql variable, never pasted into SQL text. The
# table itself is db/V1__stored_objects.sql, run as the owner so the owner owns it.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$(< /run/secrets/owner_password)" <<'SQL'
CREATE ROLE seatwave_files LOGIN PASSWORD :'owner_password';
GRANT CONNECT ON DATABASE seatwave TO seatwave_files;
REVOKE CONNECT ON DATABASE seatwave FROM PUBLIC;
CREATE SCHEMA seatwave AUTHORIZATION seatwave_files;
ALTER ROLE seatwave_files SET search_path = seatwave;
SQL
# Through the local socket of the start-up server, which listens on no TCP port during this step.
psql -v ON_ERROR_STOP=1 --username seatwave_files --dbname "$POSTGRES_DB" \
  -f /seatwave/V1__stored_objects.sql
