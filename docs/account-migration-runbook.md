# Phase 1 schema startup fix

## Root cause and required schema

`spring.jpa.hibernate.ddl-auto=validate` correctly refuses startup when the selected database is missing Phase 1 schema. It does not execute SQL migrations. This project has no Flyway/Liquibase runner; placing a SQL file in `supabase/migrations` does not apply it when Spring starts.

The unapplied migration is `supabase/migrations/202611091200_account_security.sql`. It matches all new JPA mappings under Spring Boot's default camelCase-to-snake_case naming:

| Entity | SQL object | Expected type |
| --- | --- | --- |
| AccountToken | account_tokens.hash | varchar(64), primary key |
| AccountToken | account_tokens.user_id | bigint, not null, FK users(user_id) |
| AccountToken | account_tokens.purpose | varchar(255), not null |
| AccountToken | account_tokens.expires_at | timestamp with time zone, not null (Java Instant) |
| User | users.email_verified | boolean, not null, default false |
| User | users.session_version | bigint, not null, default 0 |

SQL also enforces unique `(user_id,purpose)`, allowed purposes `verify/reset`, unique normalized emails, normalized stored emails, and token RLS/revoked anon/authenticated access. JPA schema validation alone does not verify all these security constraints; the local SQL checks do.

Existing `User`, `Transaction`, and `MonthlyBudget` mappings were inspected. The historical migration sequence builds the expected baseline, followed by Phase 1. Historical SQL narrows transaction descriptions to varchar(100) while the current entity declares 250; that predates Phase 1 and did not prevent Hibernate validation. This migration does not change transaction descriptions. If the shared schema differs from migration history, inspect it separately before applying anything; no shared database was queried during this work.

**No SQL or application schema-setting changes were needed.** The correct fix is to apply the reviewed migration to the intended database before starting this backend version. Keep `ddl-auto=validate`.

## Local verification completed

Postgres.app PostgreSQL 18.6 was available outside PATH. A fresh disposable cluster was created, using synthetic fixture data only. Supabase's `anon` and `authenticated` NOLOGIN roles were emulated locally; they are not created by the production migration.

Validated:

- All four historical migration files followed by the unchanged Phase 1 migration apply successfully in order.
- Existing user IDs, password hash strings, names, balances, transactions, and budgets remain intact. Only email case/outer spaces normalize. Existing users become unverified with session version zero.
- Duplicate normalized emails abort the entire Phase 1 transaction; no partial token table, columns, or data normalization remain.
- Email normalization/uniqueness, token purpose, FK, and one-token-per-purpose constraints reject invalid writes.
- Token RLS is enabled and anon/authenticated have no SELECT/INSERT access.
- Java 21 packaged Spring Boot application starts with `ddl-auto=validate`; `/api/hello` returns `Connected to PocketLedger backend.`
- `clean package` runs **25 tests, zero failures/errors/skips**. A separate Maven test rerun also passed.

Reproduce locally (no shared database connection is used):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
PG_BIN=/Applications/Postgres.app/Contents/Versions/latest/bin \
bash scripts/verify-account-migration.sh
```

Set paths for your machine. Requires PostgreSQL server/client tools, Java 21, Python 3, curl, and Maven wrapper dependencies. The script creates its own loopback-only cluster and database, overrides the JDBC URL, disables email delivery/frontend auto-start, builds/tests, starts the packaged app, and stops both app and cluster on exit. It retains a private temporary directory containing synthetic test data and logs for inspection; delete that directory afterward. It never applies SQL to a supplied `SUPABASE_DB_URL`.

New files for this follow-up: this runbook, `scripts/verify-account-migration.sh`, and `scripts/sql/assert-account-migration.sql`. Earlier Phase 1 edits remain untouched.

## Shared Supabase procedure — only after explicit approval

1. Schedule a short maintenance window and stop backend writers for this app. The migration takes a table lock and normalizes emails; large tables may need separate operational planning. Confirm a current backup/restore path and take a protected schema/data backup of users, transactions, and monthly_budgets. Password hashes in backups are sensitive.
2. Confirm the target Supabase project/database and backend JDBC configuration privately. Use the intended schema (`public` in this repository) and an administrative migration role. Never paste connection passwords into source control, shell history, screenshots, or logs.
3. In the project's SQL editor, run these **read-only preflight queries** and inspect results:

```sql
SELECT current_database(), current_user, current_schema();
SELECT table_name, column_name, data_type, character_maximum_length,
       numeric_precision, numeric_scale, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('users','transactions','monthly_budgets','account_tokens')
ORDER BY table_name, ordinal_position;
SELECT lower(btrim(user_email)) AS normalized_email, count(*)
FROM public.users GROUP BY 1 HAVING count(*) > 1;
SELECT count(*) AS invalid_emails FROM public.users
WHERE user_email IS NULL OR btrim(user_email) = '';
SELECT rolname FROM pg_roles WHERE rolname IN ('anon','authenticated');
SELECT count(*) AS users FROM public.users;
SELECT count(*) AS transactions FROM public.transactions;
SELECT count(*) AS budgets FROM public.monthly_budgets;
```

   Resolve duplicate/invalid addresses with account owners without deleting, merging, or changing passwords automatically. Expect the existing baseline columns from historical migrations. If Phase 1 columns/table already exist, or migration tracking says it ran, **stop and reconcile drift**; do not blindly rerun it or add `IF NOT EXISTS` to hide inconsistencies. Check constraints/indexes as well. Do not replay the old bootstrap migrations on the shared database.
4. Verify the actual JDBC role can access token records with RLS enabled. A table-owner/backend administrative role bypasses RLS; a separate restricted role requires a deliberately reviewed privilege/policy design. Do not grant token access to browser `anon`/`authenticated` roles or disable RLS to get startup working. The local owner-role test does not prove privileges for your shared JDBC role.
5. After approval, use Supabase SQL editor as the migration role. Set `search_path` to `public`, then execute the **entire contents** of `supabase/migrations/202611091200_account_security.sql` (including BEGIN and COMMIT) as one script. Do not run only the CREATE TABLE statement. The migration is intentionally one-shot and transactional. If it fails, roll back any still-open transaction and investigate before retrying. It includes Supabase-specific role revocations; missing roles are an error, not a reason to remove the security statements.
6. Confirm the new columns/table using the preflight schema query. Verify these checks before resuming traffic:

```sql
SELECT count(*) FROM public.users;
SELECT count(*) FROM public.transactions;
SELECT count(*) FROM public.monthly_budgets;
SELECT count(*) AS invalid_existing_defaults FROM public.users
WHERE email_verified IS DISTINCT FROM false OR session_version <> 0;
SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname='public' AND tablename IN ('users','account_tokens');
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid IN ('public.users'::regclass,'public.account_tokens'::regclass);
SELECT relrowsecurity FROM pg_class WHERE oid='public.account_tokens'::regclass;
SELECT has_table_privilege('anon','public.account_tokens','SELECT') AS anon_read,
       has_table_privilege('authenticated','public.account_tokens','SELECT') AS authenticated_read,
       has_table_privilege('anon','public.account_tokens','INSERT') AS anon_write,
       has_table_privilege('authenticated','public.account_tokens','INSERT') AS authenticated_write;
```

   Counts must match the backup/preflight, invalid defaults should be zero before new verification activity, RLS should be true, and public role privileges should be false. Compare protected backups to confirm password hash/record preservation; never print hashes in shared logs. Record the filename/checksum/date/executor in the team's migration history. If Supabase CLI migration history is used, reconcile this manual application with that history through the team's normal process before any later `db push`.
7. Start the backend with the intended JDBC URL and `ddl-auto=validate`. Confirm `Initialized JPA EntityManagerFactory`, `Started BackendApplication`, and a successful `/api/hello`. That endpoint alone is insufficient without successful startup/schema-validation logs. Check recovery token issuance using the backend role in no-send mode first, without sending real email.
8. Tell existing users that they must resend verification and verify ownership before logging in; their existing passwords are retained. Old sessions will be rejected. Enabling EmailJS or sending real messages remains a separate approval-gated action.

If any check fails, keep the app stopped and investigate. A committed migration's additive columns/table can remain during an application rollback; do not drop them or delete accounts to undo startup problems. Restore data only through the reviewed backup procedure if needed. This task neither applies the shared migration nor authorizes it.
