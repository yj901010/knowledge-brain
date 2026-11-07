#!/bin/sh
set -eu
for owner in identity space knowledge content learning planning collaboration activity media integration notification brainquery keycloak; do
  password=$(cat "/run/brain-db-secrets/$owner/password")
  test -n "$password" || { echo "Missing password for $owner" >&2; exit 1; }
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1     -v role="brain_$owner" -v database="brain_$owner" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'password' CONNECTION LIMIT 30;
CREATE DATABASE :"database" OWNER :"role";
REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
SQL
done
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1 <<'SQL'
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
SQL
