# 0001. Store money as fixed-precision decimals

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

PocketLedger stores income, expenses, and user balances. These values need consistent decimal precision so that saved financial records remain accurate.

## Decision

We will use Java `BigDecimal` and PostgreSQL `DECIMAL(12,2)` to store money in dollars rather than integer cents. This provides ten digits before the decimal point and two after it, as defined in [Transaction.java](../../backend/src/main/java/com/pocketledger/model/Transaction.java), [User.java](../../backend/src/main/java/com/pocketledger/model/User.java), and the [database migrations](../../supabase/migrations).

## Alternatives Considered

- **Integer cents:** Exact and practical, but would require converting the existing database values and changing the API's dollar-based amounts.
- **Floating-point values:** Convenient for calculations, but cannot represent many decimal fractions exactly, which can introduce rounding differences.

## Consequences

Positive:

- Java and PostgreSQL can represent two-decimal monetary values exactly.
- Stored amounts use the same units as the amounts shown to users.

Negative:

- Values needing more than two decimal places or ten integer digits require a schema change.
- The frontend still uses JavaScript numbers, so decimal storage alone does not guarantee accuracy throughout the application.

Follow-up:

- Add backend checks for decimal places and allowed ranges, and define how to round extra decimal places.
- Define currency handling before adding multiple currencies; records currently have no currency field.
