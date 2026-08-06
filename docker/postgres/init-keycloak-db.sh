#!/usr/bin/env bash
# =============================================================================
# Creates Keycloak's database and role alongside the application's.
#
# Runs once, from the postgres image's entrypoint, only while the data directory
# is empty. On an existing volume it never runs again — so changing KC_DB_* here
# after the first start has no effect, and the change has to be made in SQL.
#
# One Postgres instance serves both schemas: the VPS runs four containers in
# 8 GB, and a second database server for one consumer would not earn its memory.
# The two are still isolated by role — Keycloak's user owns only its own
# database and cannot read the application's tables.
# =============================================================================
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
	CREATE USER ${KC_DB_USER} WITH PASSWORD '${KC_DB_PASSWORD}';
	CREATE DATABASE ${KC_DB_NAME} OWNER ${KC_DB_USER};
	REVOKE ALL ON DATABASE ${KC_DB_NAME} FROM PUBLIC;
SQL

echo "✓ Keycloak database '${KC_DB_NAME}' created and owned by '${KC_DB_USER}'"
