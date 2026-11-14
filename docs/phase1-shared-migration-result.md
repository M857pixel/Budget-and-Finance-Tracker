> Follow-up: independent verification, shared-backend startup, and no-send API/browser auth tests are now complete. See [Phase 1 completion](phase1-completion.md). The pool-limit observations below describe the earlier migration session.

# Phase 1 shared migration execution

Applied through the user-approved IntelliJ connection for project reference `ycugszynbcpqgyattgeh` (Supabase PostgreSQL 17.6; connection test SSL yes).

## Result

The SQL tool returned **Query executed successfully** for a single atomic PostgreSQL DO statement containing the migration and its Supabase history entry. It used a five-second lock timeout, locked application tables during preservation checks, and verified the counts and full-row fingerprints both before and after the schema statements. A mismatch would raise an exception and roll back the statement.

Verified inside the successful atomic operation: **13 users, 77 transactions, 6 budgets**, with all original row fields unchanged, including password hashes, names, IDs, balances, descriptions, and amounts. No records were deleted. All 13 addresses were already normalized.

The first attempt was rejected by IntelliJ's unsafe-query check because email normalization had no WHERE clause. It made no changes. The migration now updates only addresses that differ from their normalized form:

```sql
UPDATE users SET user_email = lower(btrim(user_email))
WHERE user_email IS DISTINCT FROM lower(btrim(user_email));
```

This preserves the intended semantics and avoids unnecessary writes. Applied file: `supabase/migrations/202611091200_account_security.sql`.

SHA-256: `b8c7581f2d4e8932eb29af9bf2a36d4b8c1511bf94a98ad01fe5ca7a4b658f4f`.

The file's transaction body ran unchanged inside the atomic DO statement. The wrapper additionally recorded version `202611091200`, name `account_security`, and the migration body in `supabase_migrations.schema_migrations`. Do not reapply it or mark it as pending.

## Backup retained

Protected quick JSON snapshot:

`/Users/michaelshelton/PocketLedger-private-backups/20261109T152047Z/pre-phase1-snapshot.json`

SHA-256: `804d25bafebad1b1aaa1a855b1beb2885d6279431a6587b5b351a05eb35cf358`.

The directory is private and snapshot file permissions are 600. JSON readback was verified. It contains all public application rows (including test_records), stored password hashes, public column/constraint/index/policy metadata, and migration history. It is a quick logical snapshot, **not a complete pg_dump archive**, and no restore rehearsal was performed, as explicitly waived by the user. Keep it private and outside Git.

## Independent verification remains blocked

Immediately after the successful operation, all independent verification queries failed because the Supabase session pool had reached **15 clients** (`EMAXCONNSESSION`). No sessions were terminated. The tool's success and internal preservation checks are recorded above, but independent post-commit verification of columns, constraints, privileges, and migration history remains incomplete.

Use an already connected SQL console or SQL Editor after freeing unused connections. Do not rerun the migration. Read-only verification:

```sql
SELECT (SELECT count(*) FROM public.users) AS users,
       (SELECT count(*) FROM public.transactions) AS transactions,
       (SELECT count(*) FROM public.monthly_budgets) AS budgets;
SELECT table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema='public'
  AND (table_name='account_tokens'
       OR (table_name='users' AND column_name IN ('email_verified','session_version')))
ORDER BY table_name, column_name;
SELECT count(*) AS pending_existing_accounts
FROM public.users WHERE email_verified=false AND session_version=0;
SELECT version,name FROM supabase_migrations.schema_migrations
WHERE version='202611091200';
SELECT conname,pg_get_constraintdef(oid)
FROM pg_constraint WHERE conrelid IN ('public.users'::regclass,'public.account_tokens'::regclass);
SELECT indexname,indexdef FROM pg_indexes
WHERE schemaname='public' AND tablename IN ('users','account_tokens');
SELECT relrowsecurity FROM pg_class WHERE oid='public.account_tokens'::regclass;
SELECT has_table_privilege('anon','public.account_tokens','SELECT') AS anon_read,
       has_table_privilege('authenticated','public.account_tokens','SELECT') AS authenticated_read,
       has_table_privilege('anon','public.account_tokens','INSERT') AS anon_write,
       has_table_privilege('authenticated','public.account_tokens','INSERT') AS authenticated_write;
```

Expected counts at migration: 13/77/6; pending accounts 13 before new verification activity; migration history row present; RLS true; public read/write privileges false. New legitimate activity can change counts afterward.

Pre-migration fingerprints (no stored hashes or row contents exposed):

- Users: `79961505a5838ef753af36400c1f403a`
- Transactions: `63094cdfe6b37df5e01335480c776ba6`
- Budgets: `62e052acdcec3a32202a08a0ab0e3d3e`

Use the following only before further legitimate application changes; the new user fields are excluded:

```sql
SELECT md5((SELECT jsonb_agg(to_jsonb(t)-'email_verified'-'session_version' ORDER BY user_id)::text FROM public.users t)) AS users_fingerprint,
       md5((SELECT jsonb_agg(to_jsonb(t) ORDER BY transaction_id)::text FROM public.transactions t)) AS transactions_fingerprint,
       md5((SELECT jsonb_agg(to_jsonb(t) ORDER BY budget_id)::text FROM public.monthly_budgets t)) AS budgets_fingerprint;
```

## Application checks and team impact

After the SQL refinement, `scripts/verify-account-migration.sh` passed again on Java 21/disposable PostgreSQL 18.6: migration, collision rollback, preservation, constraints, packaged Spring Boot startup with `ddl-auto=validate`, HTTP hello, and all **25 Maven tests**. These are local checks, not shared-database authentication end-to-end tests.

Shared-database Spring Boot startup was not tested: the session pool is saturated, and terminal/backend credentials remain unavailable outside IntelliJ. To run it, use the existing secured IntelliJ Spring Boot run configuration with its private SUPABASE_DB_URL, keep ddl-auto=validate, FRONTEND_AUTO_START=false as appropriate, and AUTH_MAIL_MODE=dev. Verify successful startup before using auth flows. Do not put passwords in command arguments or tracked files.

Existing accounts become unverified and must use resend/verification before logging in on Phase 1 code. Old sessions will be rejected. Old teammate authentication code can still ignore verification; coordinate which backend version is used. No live emails were sent, and EmailJS setup/live delivery remains outstanding. Existing legacy plaintext or unsupported password formats are still rejected by PasswordHasher; the migration never rewrites them.

## Recovery

The execution was atomic; any SQL or preservation failure would roll back all changes and its history entry. After success, retain the additive schema when investigating application issues. Do not drop token tables, reset the database, delete records, or restore the entire quick snapshot over newer legitimate writes. Prefer a reviewed forward fix. If actual row damage is discovered, compare the private snapshot and selectively restore affected values under explicit approval. The snapshot does not provide full disaster-recovery coverage.

No push, merge, deployment, database reset, account deletion, or live email occurred. Earlier preflight documents describe the historical state; this report supersedes their statement that the shared migration has not run.
