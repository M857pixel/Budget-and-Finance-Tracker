UPDATE transactions SET recurring_transaction = FALSE WHERE recurring_transaction IS NULL;

ALTER TABLE transactions
    ALTER COLUMN recurring_transaction SET NOT NULL,
    ADD COLUMN recurring_next_date DATE,
    ADD COLUMN recurring_source_id BIGINT REFERENCES transactions(transaction_id) ON DELETE SET NULL,
    ADD COLUMN recurring_occurrence_date DATE,
    ADD CONSTRAINT transactions_recurring_occurrence_unique UNIQUE (recurring_source_id, recurring_occurrence_date);

-- Existing opt-ins start with the next monthly occurrence after migration.
UPDATE transactions
SET recurring_next_date = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 1
WHERE recurring_transaction = TRUE;

CREATE INDEX transactions_recurring_due_idx ON transactions (recurring_next_date, transaction_id)
WHERE recurring_transaction = TRUE;
