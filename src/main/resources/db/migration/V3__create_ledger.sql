-- STORY-004: double-entry ledger and the system accounts.
-- Every transaction has exactly two entries of equal size and opposite sign (enforced by LedgerService):
-- a negative entry on the account the money leaves, a positive one on the account it enters.
-- Money is INTEGER minor units (cents); transactions.amount is the absolute amount moved (the positive entry).
-- Timestamps are ISO-8601 UTC TEXT with exactly 3 fraction digits, e.g. 2026-11-08T12:00:00.123Z.

CREATE TABLE transactions (
    id         INTEGER PRIMARY KEY,
    type       TEXT    NOT NULL CHECK (type IN ('DEPOSIT', 'ORDER_PAYMENT', 'ORDER_REFUND')),
    amount     INTEGER NOT NULL CHECK (amount > 0),
    created_at TEXT    NOT NULL
) STRICT;

CREATE TABLE transaction_entries (
    id             INTEGER PRIMARY KEY,
    transaction_id INTEGER NOT NULL REFERENCES transactions (id),
    account_id     INTEGER NOT NULL REFERENCES accounts (id),
    amount         INTEGER NOT NULL CHECK (amount <> 0)
) STRICT;

CREATE INDEX ix_transaction_entries_transaction_id ON transaction_entries (transaction_id);
CREATE INDEX ix_transaction_entries_account_id ON transaction_entries (account_id);

-- FUNDING: where deposited money comes from (its balance goes negative). REVENUE: receives order payments (STORY-005).
INSERT INTO accounts (user_id, kind, balance, version, created_at)
VALUES (NULL, 'FUNDING', 0, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
       (NULL, 'REVENUE', 0, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
