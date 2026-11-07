#!/bin/sh
set -eu
owners="identity space knowledge content learning planning collaboration activity media integration notification brainquery keycloak"
for owner in $owners; do
  export PGPASSWORD=$(cat "/run/brain-db-secrets/$owner/password")
  psql -h "$PGHOST" -U "brain_$owner" -d "brain_$owner" -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null
  for peer in $owners; do
    [ "$owner" = "$peer" ] && continue
    if psql -h "$PGHOST" -U "brain_$owner" -d "brain_$peer" -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1; then
      echo "Cross-database access was accepted: $owner -> $peer" >&2
      exit 1
    fi
  done
done
unset PGPASSWORD
echo 'All owner logins and cross-database denial checks passed.'
