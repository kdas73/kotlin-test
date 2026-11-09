-- STORY-005: orders. An order is for one good; placing it pays for it (an ORDER_PAYMENT ledger transaction).
-- unit_price and total_amount are INTEGER minor units (cents); the unit price is the good's price at order time.
-- Timestamps are ISO-8601 UTC TEXT with exactly 3 fraction digits, e.g. 2026-11-08T12:00:00.123Z.

CREATE TABLE orders (
    id           INTEGER PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users (id),
    good_id      INTEGER NOT NULL REFERENCES goods (id),
    quantity     INTEGER NOT NULL CHECK (quantity > 0),
    unit_price   INTEGER NOT NULL CHECK (unit_price > 0),
    total_amount INTEGER NOT NULL CHECK (total_amount > 0),
    status       TEXT    NOT NULL CHECK (status IN ('PAID', 'CANCELLED')),
    version      INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL,
    CHECK (total_amount = quantity * unit_price)
) STRICT;

CREATE INDEX ix_orders_user_id ON orders (user_id);

-- The order a transaction belongs to (NULL for deposits).
ALTER TABLE transactions ADD COLUMN order_id INTEGER REFERENCES orders (id);

-- At most one ORDER_PAYMENT (and one ORDER_REFUND) per order.
CREATE UNIQUE INDEX ux_transactions_order_type ON transactions (order_id, type) WHERE order_id IS NOT NULL;
