#!/usr/bin/env bash
# Creates its own cluster; never reads SUPABASE_DB_URL or connects to shared Supabase.
set -euo pipefail
cd "$(dirname "$0")/.."
PG_BIN=${PG_BIN:-/Applications/Postgres.app/Contents/Versions/latest/bin}
for tool in initdb pg_ctl createdb psql; do
  test -x "$PG_BIN/$tool" || { echo "Set PG_BIN to a PostgreSQL bin directory containing $tool" >&2; exit 1; }
done
work=$(mktemp -d "${TMPDIR:-/tmp}/pocketledger-migration.XXXXXX")
# Free local ports; startup fails safely if another process takes one before binding.
pg_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
app_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
app_pid=''
cleanup() {
  if [ -n "$app_pid" ]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  "$PG_BIN/pg_ctl" -D "$work/data" -m fast -w stop > /dev/null 2>&1 || true
  echo "Disposable cluster stopped. Local test files/logs: $work"
}
trap cleanup EXIT
"$PG_BIN/initdb" -D "$work/data" -A trust -U pl_schema_test > "$work/init.log"
"$PG_BIN/pg_ctl" -D "$work/data" -l "$work/postgres.log" -o "-h 127.0.0.1 -p $pg_port -k $work" -w start
# This cluster contains synthetic data only; trust authentication is loopback-only.
psql_local() { "$PG_BIN/psql" -X -h 127.0.0.1 -p "$pg_port" -U pl_schema_test -d "$1" -v ON_ERROR_STOP=1 "${@:2}"; }
"$PG_BIN/createdb" -h 127.0.0.1 -p "$pg_port" -U pl_schema_test pocketledger_schema
psql_local pocketledger_schema -c 'CREATE ROLE anon NOLOGIN; CREATE ROLE authenticated NOLOGIN;'
for migration in supabase/migrations/202609212100_schema.sql supabase/migrations/202610191000_schema.sql supabase/migrations/202610191100_schema.sql supabase/migrations/202610282000_schema.sql; do
  psql_local pocketledger_schema -f "$migration"
done
psql_local pocketledger_schema <<'SQL'
INSERT INTO users(user_email,user_password_hash,user_firstname,user_lastname,user_balance)
VALUES ('Legacy@Example.com','existing-password-hash','Legacy','User',123.45);
INSERT INTO transactions(user_id,transaction_name,transaction_amount,transaction_date,transaction_description)
VALUES (1,'Existing transaction',-12.34,'2026-11-01','Keep me');
INSERT INTO monthly_budgets(user_id,budget_month,budget_amount) VALUES (1,'2026-11-01',500.00);
CREATE TABLE migration_before_users AS TABLE users;
CREATE TABLE migration_before_transactions AS TABLE transactions;
CREATE TABLE migration_before_budgets AS TABLE monthly_budgets;
SQL
# Verify that collisions abort the whole migration without partially changing schema/data.
"$PG_BIN/createdb" -h 127.0.0.1 -p "$pg_port" -U pl_schema_test -T pocketledger_schema collision_test
psql_local collision_test -c "INSERT INTO users(user_email,user_password_hash) VALUES ('legacy@example.com','collision');"
if psql_local collision_test -f supabase/migrations/202611091200_account_security.sql > "$work/collision.log" 2>&1; then
  echo 'ERROR: collision migration unexpectedly succeeded' >&2; exit 1
fi
psql_local collision_test <<'SQL'
DO $$ BEGIN
    IF to_regclass('public.account_tokens') IS NOT NULL
       OR EXISTS (SELECT FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='email_verified')
       OR (SELECT count(*) FROM users) <> 2
       OR NOT EXISTS (SELECT FROM users WHERE user_email='Legacy@Example.com') THEN
        RAISE EXCEPTION 'Collision rollback did not preserve original state';
    END IF;
END $$;
SQL
psql_local pocketledger_schema -f supabase/migrations/202611091200_account_security.sql
psql_local pocketledger_schema -f scripts/sql/assert-account-migration.sql
bash backend/mvnw -f backend/pom.xml clean package > "$work/maven.log" 2>&1
SUPABASE_DB_URL="jdbc:postgresql://127.0.0.1:$pg_port/pocketledger_schema?user=pl_schema_test" \
FRONTEND_AUTO_START=false AUTH_MAIL_MODE=dev MISTRAL_API_KEY= \
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port="$app_port" --spring.jpa.hibernate.ddl-auto=validate > "$work/backend.log" 2>&1 &
app_pid=$!
ready=false
for attempt in {1..60}; do
  if curl --fail --silent "http://127.0.0.1:$app_port/api/hello" > "$work/hello.txt"; then ready=true; break; fi
  if ! kill -0 "$app_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" != true ]; then tail -60 "$work/backend.log"; exit 1; fi
cat "$work/hello.txt"
echo
echo 'PASS: migration, collision rollback, data preservation, security constraints, Maven tests, and backend startup with validate.'
