-- Only for the disposable cluster created by verify-account-migration.sh.
DO $$
BEGIN
    IF EXISTS (
        SELECT user_id, lower(btrim(user_email)), user_password_hash, user_firstname, user_lastname, user_balance
        FROM migration_before_users
        EXCEPT SELECT user_id, user_email, user_password_hash, user_firstname, user_lastname, user_balance FROM users
    ) OR (SELECT count(*) FROM users) <> (SELECT count(*) FROM migration_before_users) THEN
        RAISE EXCEPTION 'Existing user data changed unexpectedly';
    END IF;
    IF EXISTS (TABLE migration_before_transactions EXCEPT TABLE transactions)
       OR (SELECT count(*) FROM transactions) <> (SELECT count(*) FROM migration_before_transactions) THEN
        RAISE EXCEPTION 'Transactions changed';
    END IF;
    IF EXISTS (TABLE migration_before_budgets EXCEPT TABLE monthly_budgets)
       OR (SELECT count(*) FROM monthly_budgets) <> (SELECT count(*) FROM migration_before_budgets) THEN
        RAISE EXCEPTION 'Budgets changed';
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE email_verified OR session_version <> 0) THEN
        RAISE EXCEPTION 'Incorrect existing account defaults';
    END IF;
    IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid = 'account_tokens'::regclass) THEN
        RAISE EXCEPTION 'Token RLS is disabled';
    END IF;
    IF has_table_privilege('anon','account_tokens','SELECT')
       OR has_table_privilege('authenticated','account_tokens','SELECT')
       OR has_table_privilege('anon','account_tokens','INSERT')
       OR has_table_privilege('authenticated','account_tokens','INSERT') THEN
        RAISE EXCEPTION 'Public roles can access tokens';
    END IF;
    BEGIN
        INSERT INTO users(user_email,user_password_hash) VALUES ('legacy@example.com','duplicate');
        RAISE EXCEPTION 'Duplicate email accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO users(user_email,user_password_hash) VALUES (' WrongCase@example.com ','invalid');
        RAISE EXCEPTION 'Unnormalized email accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO account_tokens(hash,user_id,purpose,expires_at) VALUES (repeat('a',64),99999,'verify',now());
        RAISE EXCEPTION 'Orphan token accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO account_tokens(hash,user_id,purpose,expires_at) VALUES (repeat('a',64),1,'other',now());
        RAISE EXCEPTION 'Invalid purpose accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END $$;
BEGIN;
INSERT INTO account_tokens(hash,user_id,purpose,expires_at) VALUES (repeat('a',64),1,'verify',now() + interval '1 day');
DO $$ BEGIN
    BEGIN
        INSERT INTO account_tokens(hash,user_id,purpose,expires_at) VALUES (repeat('b',64),1,'verify',now());
        RAISE EXCEPTION 'Multiple verification tokens accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
END $$;
ROLLBACK;
SELECT 'Migration preservation, constraints, and token permissions passed' AS result;
