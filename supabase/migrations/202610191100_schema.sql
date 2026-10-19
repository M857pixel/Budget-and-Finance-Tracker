ALTER TABLE users
ADD COLUMN user_firstName VARCHAR(20),
ADD COLUMN user_lastName VARCHAR(20),
ADD COLUMN user_balance DECIMAL(12, 2);

ALTER TABLE transactions
ALTER COLUMN transaction_description TYPE VARCHAR(100);