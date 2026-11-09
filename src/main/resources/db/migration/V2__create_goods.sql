-- STORY-003: goods sold by the shop.
-- price is INTEGER minor units (cents). Upper bounds of quantity and price are API validation rules only.
-- Timestamps are ISO-8601 UTC TEXT with exactly 3 fraction digits, e.g. 2026-11-08T12:00:00.123Z.

CREATE TABLE goods (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    quantity   INTEGER NOT NULL CHECK (quantity >= 0),
    price      INTEGER NOT NULL CHECK (price > 0),
    version    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
) STRICT;
