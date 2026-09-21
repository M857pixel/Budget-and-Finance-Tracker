/*
 Description: Contains initial database table schemas

 User Table: Holds all user information for login and transaction querying

 Transaction Table:  Holds all transaction data

 Jordan Hartman
 */

 -- Can be acessed via user_id
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL --Called password_hash, will be a password until we have encryption set up
    );

-- Can be accessed via transaction_id and user_id
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,

    name VARCHAR(50) NOT NULL,
    transaction_type VARCHAR(50),
    amount DECIMAL(12, 2) NOT NULL,
    transaction_date DATE NOT NULL,
    description VARCHAR(250),

    FOREIGN KEY (user_id)
    REFERENCES users(user_id)
    ON DELETE CASCADE
    );