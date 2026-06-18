#!/bin/bash
# Creates one database per service so each owns its schema + Flyway migrations. Runs once on first
# Postgres start (mounted into /docker-entrypoint-initdb.d/). The default POSTGRES_DB ("bank") is
# created by the image; these are the per-service databases the compose env points each service at.
set -e
# Connect to the default POSTGRES_DB (the username is NOT a database, so omitting --dbname makes
# psql default to a DB named "$POSTGRES_USER" which does not exist → "database acme does not exist").
for db in gateway transfers accounts antifraud notifications; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE $db;
    GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
done
