-- STORY-001: users, their accounts, and stored responses of idempotent POST requests.
-- Timestamps are ISO-8601 UTC TEXT with exactly 3 fraction digits, e.g. 2026-11-08T12:00:00.123Z.
-- Money is INTEGER minor units (cents).

CREATE TABLE users (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    email      TEXT    NOT NULL UNIQUE CHECK (email = lower(email)),
    created_at TEXT    NOT NULL
) STRICT;

CREATE TABLE accounts (
    id         INTEGER PRIMARY KEY,
    user_id    INTEGER UNIQUE REFERENCES users (id),
    kind       TEXT    NOT NULL CHECK (kind IN ('USER', 'FUNDING', 'REVENUE')),
    balance    INTEGER NOT NULL DEFAULT 0,
    version    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL,
    CHECK ((kind = 'USER') = (user_id IS NOT NULL)),
    CHECK (kind <> 'USER' OR balance >= 0)
) STRICT;

CREATE UNIQUE INDEX ux_accounts_system_kind ON accounts (kind) WHERE kind <> 'USER';

CREATE TABLE idempotency_keys (
    id                INTEGER PRIMARY KEY,
    idempotency_key   TEXT    NOT NULL UNIQUE,
    request_method    TEXT    NOT NULL,
    request_path      TEXT    NOT NULL,
    request_hash      TEXT    NOT NULL,
    response_status   INTEGER NOT NULL,
    response_location TEXT,
    response_body     TEXT    NOT NULL,
    created_at        TEXT    NOT NULL
) STRICT;
