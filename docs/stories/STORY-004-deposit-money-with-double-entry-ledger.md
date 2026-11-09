---
id: STORY-004
title: Deposit money with a double-entry ledger
status: Completed
created: 2026-11-08
updated: 2026-11-09
depends_on: [STORY-001]
related: []
---

# STORY-004: Deposit money with a double-entry ledger

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |
| 2026-11-09 | Pending | Test Writing | test-writer | Writing acceptance tests |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 1: 23 acceptance tests, all red for the expected reason |
| 2026-11-09 | Test Review | Ready | test-reviewer | Tests locked |
| 2026-11-09 | Ready | In Progress | implementer | Implementation started |
| 2026-11-09 | In Progress | In Review | implementer | Attempt 1: build green, 23/23 locked tests pass, lock verified |
| 2026-11-09 | In Review | Completed | reviewer | Review 1: Approved (F-1 minor) |

## Context

Every money movement in the shop is recorded with **double-entry bookkeeping**: each **transaction** has two entries
of equal size and opposite sign, one on the account the money leaves and one on the account it enters, so the books
always balance. This story introduces the ledger (`transactions` and `transaction_entries`) and the first money
movement: a **deposit** that tops up a user's account. Money for deposits comes from a system `FUNDING` account, which
stands for the outside world. The ledger migration also creates the system `REVENUE` account, which receives order
payments in STORY-005.

Deposits are `POST`s, so they are protected by the `Idempotency-Key` mechanism from STORY-001: a repeated deposit must
never credit the account twice.

## Requirements

- **R-1:** The system has exactly one `FUNDING` account and exactly one `REVENUE` account. They are system accounts with
  no user.
- **R-2:** Every transaction has a type, a positive amount, and exactly two entries: one with `-amount` on the account the
  money leaves and one with `+amount` on the account it enters. The entries of a transaction always sum to `0`.
- **R-3:** For every account, `balance` always equals the sum of the amounts of its entries. A balance change and its
  entries are committed in one database transaction.
- **R-4:** A client can deposit a positive amount into a user's account. The amount is a money string in the project's
  money format (STORY-001) from `"0.01"` to `"10000000.00"`. The deposit is a `DEPOSIT` transaction from the `FUNDING`
  account to the user's account.
- **R-5:** A deposit requires an `Idempotency-Key` and follows the idempotency rules of STORY-001. A repeated deposit with
  the same key credits the account only once.
- **R-6:** Concurrent deposits to the same account with different keys never lose or duplicate money: each one either
  succeeds or fails without any effect, and the final balance equals the sum of the successful deposits.
- **R-7:** A client can read a transaction with its entries by ID. An unknown ID returns `404`.
- **R-8:** A deposit to an unknown user returns `404` and changes nothing. An invalid amount returns `400` and changes
  nothing.
- **R-9:** All money amounts in transaction responses (`amount`, `entries[].amount`) are money strings; entry amounts are
  signed (`"-50.00"`, `"50.00"`). The database stores them as integer minor units.

## Out of scope

- Withdrawals and transfers between users.
- Order payments and refunds (STORY-005, STORY-006). Their transaction types are already allowed by the schema.
- An API for system accounts. Their balances are checked with SQL.
- Account statements (STORY-007).

## Technical details

### API

| Method | Path                                   | Request body | Success response                      | Error responses            |
|--------|----------------------------------------|--------------|---------------------------------------|----------------------------|
| `POST` | `/api/v1/users/{id}/account/deposits`  | `{amount}`   | `201` + `Location` + transaction      | `400`, `404`, `409`, `422` |
| `GET`  | `/api/v1/transactions/{id}`            | —            | `200` + transaction                   | `404`                      |

**Deposit request** (`Content-Type: application/json`, header `Idempotency-Key` required):

```json
{ "amount": "50.00" }
```

**Transaction response** (`POST` 201 and `GET` 200). `Location` path ends with `/api/v1/transactions/{id}`. `entries`
are ordered by `amount` ascending (the negative entry first):

```json
{
  "id": 10,
  "type": "DEPOSIT",
  "amount": "50.00",
  "createdAt": "2026-11-08T12:00:00.123Z",
  "entries": [
    { "accountId": 1, "amount": "-50.00" },
    { "accountId": 7, "amount": "50.00" }
  ]
}
```

Here `accountId` 1 is the `FUNDING` account and 7 is the user's account. In the database this transaction has
`amount = 5000` and entries `-5000` / `5000` (minor units).

**Validation** (all → `400`): `amount` required, a money string (STORY-001 *Money format*) from `"0.01"` to
`"10000000.00"`, so a JSON number or a badly formatted string is rejected; malformed JSON.

**Errors** (`ProblemDetail`): idempotency errors as in STORY-001; `404` for an unknown user or transaction; `409`, title
`Concurrent modification`, when a concurrent change to the same account makes this request fail (no effect; the client
may retry with the same key).

Processing order for a deposit: idempotency header checks → validation (`400`) → user exists (`404`) → post the
transaction.

### Data model

Migration `V{n}__create_ledger.sql`, where `n` is the next free Flyway version at implementation time:

```sql
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

INSERT INTO accounts (user_id, kind, balance, version, created_at)
VALUES (NULL, 'FUNDING', 0, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
       (NULL, 'REVENUE', 0, 0, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
```

- All money columns (`transactions.amount`, `transaction_entries.amount`, `accounts.balance`) are minor units (cents).
- Sign convention: a negative entry means money leaves the account, a positive entry means money enters it.
- `transactions.amount` is the absolute amount moved and equals the positive entry.
- The `FUNDING` balance goes negative as money is deposited (it represents money that came from outside). The user
  account `CHECK (balance >= 0)` from STORY-001 still applies.

### Affected code

- `ledger/`: `Transaction.kt` (aggregate root with its entries), `TransactionEntry.kt`, `TransactionRepository.kt`,
  `LedgerService.kt` (posts a balanced transaction and updates both account balances), `TransactionController.kt`,
  `TransactionDtos.kt`.
- `account/`: `DepositController.kt` (or an endpoint in the existing account controller), deposit logic in a service,
  `@Version` on `Account`.
- `common/idempotency/`: reused as is.

### Constraints and notes

- The ledger service is the only code that changes `accounts.balance`. It is reused by STORY-005 and STORY-006.
- Keep the deposit transaction short (SQLite single writer). Optimistic-locking or `SQLITE_BUSY` failures must end as
  `409 Concurrent modification`, never as `500`. A retry inside the service is allowed.
- Spring Data JDBC: the entries may be a `@MappedCollection` of the transaction aggregate; the back-reference column is
  `transaction_id`.

## Acceptance criteria

Tests share one database: system-account balances are checked as differences (before/after), and every test uses its
own users and keys. API money values are strings in major units; database values are integers in minor units
(`"50.00"` ↔ `5000`).

- **AC-1:** Given the migrations have run, then `accounts` has exactly one row with `kind = 'FUNDING'` and exactly one
  with `kind = 'REVENUE'`, both with `user_id IS NULL`.
- **AC-2:** Given a new user with balance `"0.00"`, when the client sends `POST /api/v1/users/{id}/account/deposits`
  with a new key and `{"amount":"50.00"}`, then the response is `201`, the `Location` path ends with
  `/api/v1/transactions/{txId}`, the body has `id` = txId, `type` `"DEPOSIT"`, `amount` `"50.00"`, `createdAt` ending in
  `Z`, and `entries`
  `[{"accountId":<FUNDING id>,"amount":"-50.00"},{"accountId":<user account id>,"amount":"50.00"}]`, where every
  `amount` is a JSON string. In the database, the user account has `balance = 5000`, the `FUNDING` balance decreased by
  exactly `5000`, `transactions` has a row with that ID, `type = 'DEPOSIT'` and `amount = 5000`, and
  `transaction_entries` has exactly 2 rows for it, `-5000` and `5000`.
- **AC-3:** Given a user who deposited `"50.00"`, when the client deposits `"30.25"` with another new key, then
  `GET /api/v1/users/{id}/account` returns `balance` `"80.25"` and the account row has `balance = 8025`.
- **AC-4:** Given a deposit returned `201` with body `B`, when the client sends `GET /api/v1/transactions/{txId}`, then
  the response is `200` with a body JSON-equal to `B`. For an unknown transaction ID the response is `404` with
  `Content-Type: application/problem+json`.
- **AC-5:** Given a deposit of `"50.00"` with key `K` returned `201`, when the client sends the same deposit with key `K`
  again, then the response is `201` with `Idempotent-Replayed: true`, the same `Location` and a JSON-equal body; the user
  account has `balance = 5000` and exactly one entry in `transaction_entries`.
- **AC-6:** Given a new user and a new key `K`, when the client sends 10 identical deposits of `"10.00"` with key `K`
  concurrently, then the user account has `balance = 1000` and exactly one entry, every response is `201` (JSON-equal
  bodies) or `409` with title `Request in progress`, and at least one is `201`.
- **AC-7:** Given a new user, when the client sends 5 deposits of `"1.00"` concurrently, each with its own new key, then
  every response is `201` or `409`, the user account `balance` equals `100 ×` (number of `201` responses), the user
  account has exactly that many entries, and at least one response is `201`.
- **AC-8:** Given a user with balance `"0.00"`, when the client sends a deposit with (a) no `amount`, (b) `amount`
  `"0.00"`, (c) `amount` `"-1.00"`, (d) `amount` `"10000000.01"`, (e) `amount` as a JSON number `50`, (f) `amount` as a
  JSON number `50.00`, (g) `amount` `"50"`, (h) `amount` `"50.5"`, (i) `amount` `"50.000"`, (j) `amount` `"50,00"`, or
  (k) malformed JSON, each with a new key, then each response is `400` with `Content-Type: application/problem+json`,
  the account row keeps `balance = 0`, and the number of rows in `transactions` is unchanged. A deposit of `"0.01"`
  (row `amount = 1`) and one of `"10000000.00"` (row `amount = 1000000000`) are accepted (`201`).
- **AC-9:** Given no user with a given ID, when the client sends a valid deposit to it, then the response is `404` with
  `Content-Type: application/problem+json`, the number of rows in `transactions` is unchanged, and the `FUNDING`
  balance is unchanged.
- **AC-10:** Given a user, when the client sends a valid deposit without an `Idempotency-Key`, then the response is `400`
  with title `Idempotency-Key header required` and the balance is unchanged.
- **AC-11:** Given the deposits of the tests above, then for every row in `transactions` there are exactly 2 rows in
  `transaction_entries`, they sum to `0`, and the positive one equals `transactions.amount`; and for every account,
  `balance = COALESCE(SUM(transaction_entries.amount), 0)` over its entries.

- **AC-12:** Given a new user, when the client deposits `"0.10"` and then `"0.20"` (new keys), then
  `GET /api/v1/users/{id}/account` returns `balance` `"0.30"` exactly and the row has `balance = 30`. When the same user
  then deposits `"0.29"` ten times (new keys), then the balance is `"3.20"` (row `balance = 320`), and the deposit
  responses have `amount` `"0.29"` and entries `"-0.29"` / `"0.29"`. For these rows, `typeof(balance)`,
  `typeof(transactions.amount)` and `typeof(transaction_entries.amount)` are `'integer'`.

## Open questions

None.

## Acceptance tests

<!--
Test writer only. Append-only: add one "### Test design N — YYYY-MM-DD" subsection per round and never edit earlier rounds.
Tests are black-box (HTTP API + SQL), with no imports from production code, in
src/test/kotlin/com/example/kotlintest/acceptance/Story<NNN><Name>AcceptanceTest.kt.

### Test design 1 — YYYY-MM-DD

**Test review findings addressed:** (round 2+ only) one line per finding of the latest test review: `T-n: how it was fixed`.

**Files:**
- `src/test/kotlin/com/example/kotlintest/acceptance/StoryNNNSomethingAcceptanceTest.kt`

**AC coverage:**

| AC   | Test (class › method)                                        |
|------|--------------------------------------------------------------|
| AC-1 | `StoryNNNSomethingAcceptanceTest` › `AC-1 creates ...`       |

**Red run:** `./gradlew test`: compiles; N new tests fail, all other tests pass.

| Test | Failure reason (expected before implementation) |
|------|--------------------------------------------------|
| `AC-1 creates ...` | 404 Not Found: endpoint does not exist yet |

**Notes:** test data setup, assumptions, anything the test reviewer should know.
-->

### Test design 1 — 2026-11-09

**Files:**
- `src/test/kotlin/com/example/kotlintest/acceptance/Story004DepositMoneyAcceptanceTest.kt`

**AC coverage:**

| AC   | Test (class › method) |
|------|-----------------------|
| AC-1 | `Story004DepositMoneyAcceptanceTest` › `AC-1 migrations create exactly one FUNDING and one REVENUE account without a user` |
| AC-2 | `Story004DepositMoneyAcceptanceTest` › `AC-2 deposits 50_00 and returns 201 with Location, the transaction and its ledger rows` |
| AC-3 | `Story004DepositMoneyAcceptanceTest` › `AC-3 a second deposit adds to the balance` |
| AC-4 | `Story004DepositMoneyAcceptanceTest` › `AC-4 reads a transaction by id and returns 404 problem detail for an unknown id` |
| AC-5 | `Story004DepositMoneyAcceptanceTest` › `AC-5 a repeated deposit with the same key is replayed and credits the account once` |
| AC-6 | `Story004DepositMoneyAcceptanceTest` › `AC-6 concurrent identical deposits with the same key credit the account once` |
| AC-7 | `Story004DepositMoneyAcceptanceTest` › `AC-7 concurrent deposits with different keys never lose or duplicate money` |
| AC-8 | `Story004DepositMoneyAcceptanceTest` › `AC-8 (a)` … `AC-8 (k)` (one test per invalid case: no amount, `"0.00"`, `"-1.00"`, `"10000000.01"`, JSON number `50`, JSON number `50.00`, `"50"`, `"50.5"`, `"50.000"`, `"50,00"`, malformed JSON) and `AC-8 accepts the boundary amounts 0_01 and 10000000_00` |
| AC-9 | `Story004DepositMoneyAcceptanceTest` › `AC-9 a deposit to an unknown user returns 404 and changes nothing` |
| AC-10 | `Story004DepositMoneyAcceptanceTest` › `AC-10 a deposit without Idempotency-Key returns 400 and leaves the balance unchanged` |
| AC-11 | `Story004DepositMoneyAcceptanceTest` › `AC-11 every transaction is balanced and every account balance equals the sum of its entries` |
| AC-12 | `Story004DepositMoneyAcceptanceTest` › `AC-12 small deposits add up exactly and are stored as integers` |

**Red run:** `python3 scripts/app.py test --rerun` (`./gradlew test`): compiles; 163 tests, 23 failures. All 23 new
tests fail, all 140 pre-existing tests pass.

| Test | Failure reason (expected before implementation) |
|------|--------------------------------------------------|
| `AC-1 ...` | `FUNDING accounts expected 1 but was 0`: the ledger migration does not exist yet |
| `AC-2 ...` | `exactly one FUNDING account expected`: no `FUNDING` account yet (the endpoint is missing too) |
| `AC-3 ...`, `AC-4 ...`, `AC-11 ...` | `expected 201 but was 404`: deposit endpoint does not exist yet |
| `AC-5 ...`, `AC-6 ...`, `AC-7 ...`, `AC-10 ...`, `AC-12 ...`, `AC-8 accepts the boundary amounts ...` | `404 Not Found` (`No static resource api/v1/users/{id}/account/deposits`): deposit endpoint does not exist yet |
| `AC-8 (a)` … `AC-8 (k)`, `AC-9 ...` | `[SQLITE_ERROR] no such table: transactions`: the ledger tables do not exist yet |

**Notes:**
- The database is shared between tests and test classes and nothing is deleted. Every test registers its own users
  through `POST /api/v1/users` (STORY-001) and reads the user's account ID with SQL; every request uses a new random
  `Idempotency-Key` unless the AC reuses one. The `FUNDING` balance is checked as a before/after difference (AC-2, AC-9);
  this relies on test methods not running in parallel (the project has no JUnit parallel execution).
- AC-2 also checks in SQL that the `-5000` entry is on the `FUNDING` account and the `5000` entry on the user's account
  (R-2, R-4, *Technical details*). Entries in the response are checked field by field (`accountId` integer, `amount`
  string) in the documented order (negative first); extra fields in an entry are not forbidden.
- AC-6 and AC-7 send 10 and 5 requests on virtual threads released by one latch (60 s join timeout). In AC-7 a non-`201`
  response must be a `409` `ProblemDetail`; its title is not asserted because AC-7 only names the status.
- AC-11 is order-independent: the test posts its own three deposits, then checks the invariants over **all** rows of
  `transactions`, `transaction_entries` and `accounts` in the shared in-memory database. Any other test (including the
  implementer's own tests) that leaves an account balance not backed by entries, or an unbalanced transaction, in that
  database will make AC-11 fail.
- AC-12 checks `typeof(...) = 'integer'` for the user's account row and for all 12 transactions of that user and their
  entries.
- AC-9 uses user ID `Long.MAX_VALUE` and asserts first that no such user exists.

## Test review

<!--
Test reviewer only. Append-only: add one "### Test review N — YYYY-MM-DD — Verdict: Approved | Changes requested" subsection per review.

### Test review 1 — YYYY-MM-DD — Verdict: Approved

**AC coverage:**

| AC   | Covered | Assertion matches Given/When/Then | Note |
|------|---------|-----------------------------------|------|
| AC-1 | yes     | yes                               |      |

**Quality checks:** black-box only (no production imports): yes · deterministic: yes · isolated test data: yes ·
no over-specification: yes · red for the expected reason (own run): yes.

**Findings:**

| ID  | Severity | Location                  | Description |
|-----|----------|---------------------------|-------------|
| T-1 | blocking | `StoryNNN...Test.kt:42`   | ...         |

**Locked tests:** (Approved only; paste the output of `python3 scripts/locked-tests.py hash <files>`)

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/StoryNNNSomethingAcceptanceTest.kt` | <sha256> |
-->

### Test review 1 — 2026-11-09 — Verdict: Approved

**AC coverage:**

| AC    | Covered | Assertion matches Given/When/Then | Note |
|-------|---------|-----------------------------------|------|
| AC-1  | yes     | yes | Counts `FUNDING` and `REVENUE` rows (exactly 1 each) and checks `user_id IS NULL`. |
| AC-2  | yes     | yes | Status, `Location` path suffix, `id` integer, `type`, `amount` string, `createdAt` ISO UTC ending in `Z`, entries in order with integer `accountId` and string `amount`; SQL: user balance `5000`, `FUNDING` delta `-5000`, `transactions` row (`DEPOSIT`, `5000`), exactly 2 entries `-5000` on `FUNDING` and `5000` on the user account. |
| AC-3  | yes     | yes | `GET /api/v1/users/{id}/account` `balance` `"80.25"` (string) and row `8025`. |
| AC-4  | yes     | yes | `GET` body JSON-equal to the `POST` body; unknown ID → `404` `application/problem+json`. |
| AC-5  | yes     | yes | Replay `201`, `Idempotent-Replayed: true`, identical `Location`, JSON-equal body, balance `5000`, exactly one entry. |
| AC-6  | yes     | yes | 10 same-key requests released by one latch; non-`201` must be `409` `Request in progress`; all `201` bodies JSON-equal; at least one `201`; balance `1000`, one entry. Robust to any interleaving. |
| AC-7  | yes     | yes | 5 different-key requests; non-`201` must be `409` (ProblemDetail, title not asserted, as in the AC); balance and entry count equal `100 ×` / `1 ×` the number of `201`s; at least one `201`. |
| AC-8  | yes     | yes | One test per case (a)–(k), each with a new user and key: `400` `application/problem+json`, balance `0`, `transactions` row count unchanged. Boundaries `"0.01"` / `"10000000.00"` → `201` with rows `1` / `1000000000`. |
| AC-9  | yes     | yes | Asserts the user ID does not exist first; `404` `application/problem+json`, `transactions` count and `FUNDING` balance unchanged. |
| AC-10 | yes     | yes | `400` with title `Idempotency-Key header required`, balance `0`. |
| AC-11 | yes     | yes | Posts its own 3 deposits, then checks over the whole DB: every transaction has exactly 2 entries summing to `0` with the positive one equal to `amount`; every account `balance = COALESCE(SUM(entries), 0)`. Order-independent. |
| AC-12 | yes     | yes | `"0.30"` / `30` after `0.10 + 0.20`; `"3.20"` / `320` after ten `0.29`; each `0.29` response has `amount` and entries `"-0.29"` / `"0.29"` as strings; `typeof = 'integer'` for the account row and all 12 transactions and their 24 entries. |

**Quality checks:** black-box only (no production imports; only `RestTestClient` and `JdbcTemplate`): yes ·
deterministic (no sleeps, no clock dependence; concurrency assertions hold for every interleaving; no JUnit/Gradle
parallel execution configured, so the `FUNDING` before/after deltas are stable): yes · isolated test data (own users via
`POST /api/v1/users`, random keys, system balances as deltas): yes · no over-specification (paths, fields, tables and
status codes match *Technical details*; no internal design dictated): yes · AC-prefixed readable names, English: yes ·
no `@Disabled` / tags: yes · no changes to `build.gradle.kts` or `src/test/resources`: yes · red for the expected
reason (own run): yes.

**Red run (own):** `python3 scripts/app.py test --rerun`: compiles; 163 tests, 23 failures, 0 errors, 0 skipped. All 23
failures are in `Story004DepositMoneyAcceptanceTest`; all 140 other tests pass. Failure reasons as listed in Test
design 1: `FUNDING accounts expected 1 but was 0` (AC-1, AC-2), `404 No static resource api/v1/users/{id}/account/deposits`
(AC-3, AC-5, AC-6, AC-7, AC-10, AC-11, AC-12, AC-8 boundaries), `[SQLITE_ERROR]` on `SELECT count(*) FROM transactions`
(no such table; AC-8 (a)–(k), AC-9). None is caused by a test bug.

**Findings:** none.

Notes for the implementer (not findings): AC-11 checks the ledger invariants over **all** rows of the shared in-memory
test database, so any additional test (yours included) that changes `accounts.balance` without matching
`transaction_entries`, or writes an unbalanced transaction, will make AC-11 fail. AC-6/AC-7 require `409` (never `500`)
for idempotency and concurrency conflicts.

**Locked tests:**

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/Story004DepositMoneyAcceptanceTest.kt` | 4692d1597c66694eab1d87e624f761bd3e888e3ce44044a4bc751c937acf1f2f |

## Execution evidence

<!--
Implementer only. Append-only: add one "### Attempt N — YYYY-MM-DD" subsection per attempt and never edit earlier attempts.

### Attempt 1 — YYYY-MM-DD

**Summary:** what was implemented.

**Review findings addressed:** (attempt 2+ only) one line per finding of the latest review: `F-n: how it was fixed`.

**Changed files:**
- `path/to/File.kt`: what changed

**Migrations:** `V1__create_books.sql`, or "none".

**Acceptance criteria evidence:**

| AC   | Locked acceptance test (now passing)          | Additional tests by the implementer |
|------|-----------------------------------------------|-------------------------------------|
| AC-1 | `StoryNNN...AcceptanceTest` › `AC-1 ...`      | `BookServiceTest` › `...` (optional) |

**Build:** `./gradlew build`: BUILD SUCCESSFUL, N tests, 0 failures (paste the relevant tail of the output).

**Locked tests verify:** output of `python3 scripts/locked-tests.py verify STORY-NNN` (must be all `OK`).

**Deviations and notes:** none / description. Test defects (if any): test, reason, conflicting requirement.
-->

### Attempt 1 — 2026-11-09

**Summary:** Added the double-entry ledger (`ledger/` package): the migration that creates `transactions` and
`transaction_entries` and the `FUNDING` / `REVENUE` system accounts; the `Transaction` aggregate with its entries as a
`@MappedCollection` (back-reference `transaction_id`); and `LedgerService.post`, the only code that changes
`accounts.balance`. It saves both accounts with their `@Version` (the version already existed on `Account`) and saves a
balanced transaction (`-amount` / `+amount`) in the caller's database transaction. Added
`POST /api/v1/users/{userId}/account/deposits`. It is wrapped in `IdempotentExecutor`, which is reused unchanged. The
`amount` is a money string from `"0.01"` to `"10000000.00"` (`StringOnlyDeserializer` + `@MoneyAmount`). Added
`GET /api/v1/transactions/{id}`, with entries ordered by amount ascending and `404 Transaction not found` for an
unknown ID. SQLite lock conflicts (`SQLITE_BUSY`, and `SQLITE_LOCKED` from the shared-cache test DB) anywhere in a
`DataAccessException` / `TransactionException` cause chain now map to `409 Concurrent modification` instead of `500`.

**Changed files:**
- `src/main/resources/db/migration/V3__create_ledger.sql`: new; tables, indexes and system accounts exactly as in *Technical details*.
- `src/main/kotlin/com/example/kotlintest/ledger/Transaction.kt`: new; `TransactionType`, `Transaction` aggregate root, `TransactionNotFoundException` (`404 Transaction not found`).
- `src/main/kotlin/com/example/kotlintest/ledger/TransactionEntry.kt`: new; entry entity (`transaction_entries`).
- `src/main/kotlin/com/example/kotlintest/ledger/TransactionRepository.kt`: new; Spring Data JDBC repository.
- `src/main/kotlin/com/example/kotlintest/ledger/LedgerService.kt`: new; `post(type, from, to, amount)` with `subtractExact`/`addExact`, and `get(id)`.
- `src/main/kotlin/com/example/kotlintest/ledger/TransactionDtos.kt`: new; `TransactionResponse` / `TransactionEntryResponse` (money strings, entries sorted ascending).
- `src/main/kotlin/com/example/kotlintest/ledger/TransactionController.kt`: new; `GET /api/v1/transactions/{id}`.
- `src/main/kotlin/com/example/kotlintest/account/DepositController.kt`: new; `POST /api/v1/users/{userId}/account/deposits` through `IdempotentExecutor`, `201` + `Location`.
- `src/main/kotlin/com/example/kotlintest/account/DepositService.kt`: new; user check (`404`), then posts a `DEPOSIT` from `FUNDING` to the user's account.
- `src/main/kotlin/com/example/kotlintest/account/AccountDtos.kt`: added `DepositRequest`.
- `src/main/kotlin/com/example/kotlintest/account/AccountRepository.kt`: added `findByKindAndUserIdIsNull`.
- `src/main/kotlin/com/example/kotlintest/account/AccountService.kt`: added `getSystemAccount(kind)`.
- `src/main/kotlin/com/example/kotlintest/common/SqliteErrors.kt`: added `Throwable.isSqliteLockConflict()` (SQLite error codes 5 and 6).
- `src/main/kotlin/com/example/kotlintest/common/ApiExceptionHandler.kt`: added `handleDatabaseError` (`DataAccessException`, `TransactionException`): `409 Concurrent modification` for a lock conflict, otherwise the existing `500`; shared `concurrentModification()` response.
- `AGENTS.md`: added the `404 Transaction not found` title, the lock-conflict `409` mapping, and the ledger rule.
- Tests (implementer, not locked): `ledger/LedgerServiceTest.kt`, `ledger/TransactionResponseTest.kt`, `common/SqliteErrorsTest.kt`, `account/DepositApiTest.kt`.

**Migrations:** `V3__create_ledger.sql`.

**Acceptance criteria evidence:**

| AC    | Locked acceptance test (now passing) | Additional tests by the implementer |
|-------|--------------------------------------|-------------------------------------|
| AC-1  | `Story004DepositMoneyAcceptanceTest` › `AC-1 migrations create exactly one FUNDING and one REVENUE account without a user` | — |
| AC-2  | `Story004DepositMoneyAcceptanceTest` › `AC-2 deposits 50_00 and returns 201 with Location, the transaction and its ledger rows` | `LedgerServiceTest` › `post moves the amount between the accounts with their loaded versions and records balanced entries` |
| AC-3  | `Story004DepositMoneyAcceptanceTest` › `AC-3 a second deposit adds to the balance` | — |
| AC-4  | `Story004DepositMoneyAcceptanceTest` › `AC-4 reads a transaction by id and returns 404 problem detail for an unknown id` | `DepositApiTest` › `an unknown transaction returns 404 titled Transaction not found`, `a non-numeric transaction id returns a 400 problem detail`; `TransactionResponseTest` › `money is formatted as strings and entries are ordered by amount ascending` |
| AC-5  | `Story004DepositMoneyAcceptanceTest` › `AC-5 a repeated deposit with the same key is replayed and credits the account once` | `DepositApiTest` › `reusing a key with a different amount returns 422 and changes nothing` |
| AC-6  | `Story004DepositMoneyAcceptanceTest` › `AC-6 concurrent identical deposits with the same key credit the account once` | — |
| AC-7  | `Story004DepositMoneyAcceptanceTest` › `AC-7 concurrent deposits with different keys never lose or duplicate money` | `SqliteErrorsTest` (4 tests: lock-conflict detection, `409` mapping, other DB errors stay `500`) |
| AC-8  | `Story004DepositMoneyAcceptanceTest` › `AC-8 (a)` … `AC-8 (k)`, `AC-8 accepts the boundary amounts 0_01 and 10000000_00` | `DepositApiTest` › `a rejected deposit is not recorded, so its key can be used for a valid deposit`; `LedgerServiceTest` › `post rejects a non-positive amount without writing` |
| AC-9  | `Story004DepositMoneyAcceptanceTest` › `AC-9 a deposit to an unknown user returns 404 and changes nothing` | `DepositApiTest` › `a deposit to an unknown user returns 404 titled User not found` |
| AC-10 | `Story004DepositMoneyAcceptanceTest` › `AC-10 a deposit without Idempotency-Key returns 400 and leaves the balance unchanged` | — |
| AC-11 | `Story004DepositMoneyAcceptanceTest` › `AC-11 every transaction is balanced and every account balance equals the sum of its entries` | `LedgerServiceTest` › `post rejects a transaction from an account to itself`, `post fails on a balance overflow instead of wrapping around` |
| AC-12 | `Story004DepositMoneyAcceptanceTest` › `AC-12 small deposits add up exactly and are stored as integers` | — |

**Build:** `python3 scripts/app.py build` (`gradlew.bat build`): BUILD SUCCESSFUL, 178 tests, 0 failures.

```
$ gradlew.bat build
> Task :build
BUILD SUCCESSFUL in 15s
  com.example.kotlintest.acceptance.Story001RegisterUsersAcceptanceTest: 26 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story002UpdateUserAcceptanceTest: 14 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story003ManageGoodsAcceptanceTest: 38 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story004DepositMoneyAcceptanceTest: 23 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story009CodePointLengthAcceptanceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.account.DepositApiTest: 5 tests, 0 failures, 0 errors, 0 skipped
  ...
  com.example.kotlintest.common.SqliteErrorsTest: 4 tests, 0 failures, 0 errors, 0 skipped
  ...
  com.example.kotlintest.ledger.LedgerServiceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.ledger.TransactionResponseTest: 1 tests, 0 failures, 0 errors, 0 skipped
  ...
  TOTAL: 178 tests, 0 failures, 0 errors, 0 skipped
build: SUCCESSFUL
```

A separate `./gradlew clean build` also ended with `BUILD SUCCESSFUL`. I reran
`Story004DepositMoneyAcceptanceTest` 3 more times with `--rerun`: 23 tests, 0 failures each time.

**Locked tests verify:**

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story004DepositMoneyAcceptanceTest.kt
STORY-004-deposit-money-with-double-entry-ledger: 1/1 locked tests unchanged
```

**Deviations and notes:**
- No test defects.
- Compiler warnings: the clean build prints 3 `Unnecessary safe call on a non-null receiver of type 'ByteArray'`
  warnings. All 3 are in locked acceptance tests (`Story003...:620`, `Story004...:514`, `Story009...:146`), and the
  implementer must not change those files. Production code and the implementer's tests compile without warnings.
- Concurrency: in the shared-cache in-memory test DB, competing writers fail at once with `SQLITE_LOCKED` (code 6;
  `busy_timeout` does not apply to shared-cache table locks). Before the new handler this was a `500`, and AC-7 failed
  once during development. Now it is a `409 Concurrent modification` and the transaction is rolled back with no
  idempotency record, so the client can retry with the same key (R-6). No in-service retry was added; the story allows
  one but does not require it. On the file DB (WAL, `busy_timeout=5000`), writers wait instead. Manual check in sandbox
  mode: 8 parallel deposits of `"1.00"` with different keys all returned `201`. After a `"50.00"` deposit and its replay
  (`Idempotent-Replayed: true`, same body), the balance was exactly `"58.00"`.
- `LedgerService.post` takes the `Account`s the caller read and saves them with their versions, so a balance check by
  the caller (STORY-005) stays valid until commit. It does not check that a user balance stays non-negative; the
  schema `CHECK` is the backstop. That check is up to STORY-005 (payments), which is out of scope here.

## Review

<!--
Reviewer only. Append-only: add one "### Review N — YYYY-MM-DD — Verdict: Approved | Changes requested" subsection per review.

### Review 1 — YYYY-MM-DD — Verdict: Changes requested

**Requirements:**

| Req | Met | Note |
|-----|-----|------|
| R-1 | yes |      |

**Acceptance criteria:**

| AC   | Result | How checked |
|------|--------|-------------|
| AC-1 | pass   |             |

**Build:** `./gradlew build`: result observed by the reviewer.

**Locked tests verify:** output of `python3 scripts/locked-tests.py verify STORY-NNN` run by the reviewer; locked tests
not skipped or weakened (no `@Disabled`, Gradle test filters, or test-resource changes affecting them).

**Findings:**

| ID  | Severity | Location          | Description |
|-----|----------|-------------------|-------------|
| F-1 | blocking | `File.kt:42`      | ...         |

Severity: `blocking` (must be fixed before approval) or `minor` (should be fixed, does not block approval).
-->

### Review 1 — 2026-11-09 — Verdict: Approved

**Requirements:**

| Req | Met | Note |
|-----|-----|------|
| R-1 | yes | `V3__create_ledger.sql:25-27` inserts exactly one `FUNDING` and one `REVENUE` account with `user_id NULL`; `AccountRepository.findByKindAndUserIdIsNull` / `AccountService.getSystemAccount` read them. Sandbox DB: `(1, FUNDING, NULL)`, `(2, REVENUE, NULL)`. |
| R-2 | yes | `LedgerService.post` (`LedgerService.kt:36-55`) requires `amount > 0` and two different accounts, and saves a `Transaction` with exactly the entries `-amount` (from) / `+amount` (to). The schema `CHECK`s and type list match *Technical details*. |
| R-3 | yes | `post` updates both balances and saves the transaction with its entries in one `@Transactional` call, which joins the `IdempotentExecutor` transaction. It is the only code that writes `accounts.balance` (grep: only `AccountService.create` with `balance = 0` and `LedgerService.kt:42-43`). Sandbox and AC-11: no account whose balance differs from the sum of its entries. |
| R-4 | yes | `DepositController` `POST /api/v1/users/{userId}/account/deposits`; `DepositRequest` uses `StringOnlyDeserializer` + `@MoneyAmount(min = "0.01", max = "10000000.00")` + `@NotNull`; `DepositService` posts `DEPOSIT` from `FUNDING` to the user's account. |
| R-5 | yes | Wrapped in `IdempotentExecutor.execute` (reused unchanged). Replay checked in sandbox: `201`, same `Location`, `Idempotent-Replayed: true`, identical body, balance credited once. |
| R-6 | yes | Optimistic `@Version` on both accounts. `SQLITE_BUSY`/`SQLITE_LOCKED` now become `409 Concurrent modification` (`ApiExceptionHandler.handleDatabaseError`) with full rollback and no idempotency record. The executor reserves the key first, so the transaction is a write transaction from its first statement, which serializes writers on the file DB. Sandbox (file DB, WAL): 30 concurrent deposits to 3 users, all contending on the `FUNDING` row: 30 × `201`, balances exact, ledger invariants hold. |
| R-7 | yes | `TransactionController` `GET /api/v1/transactions/{id}`; `TransactionNotFoundException` → `404 Transaction not found` (`application/problem+json`, checked in sandbox). |
| R-8 | yes | `@Valid` runs before the method body (validation `400` before the user check, also observed in sandbox: an unknown user with an invalid amount → `400`); unknown user → `UserNotFoundException` `404` inside the rolled-back transaction. |
| R-9 | yes | `TransactionResponse` formats `amount` and the signed `entries[].amount` with `Money.format`; DB columns are `INTEGER` minor units (AC-12 checks `typeof = 'integer'`). |

**Acceptance criteria:**

| AC    | Result | How checked |
|-------|--------|-------------|
| AC-1  | pass | Locked test passed in my build; sandbox DB query shows exactly one `FUNDING` and one `REVENUE` row without user. |
| AC-2  | pass | Locked test passed; sandbox: `201`, `Location: /api/v1/transactions/1`, body with `"-50.00"` on `FUNDING` (id 1) first and `"50.00"` on the user account. |
| AC-3  | pass | Locked test passed. |
| AC-4  | pass | Locked test passed; sandbox: `GET` body identical to the `POST` body; unknown ID → `404` `application/problem+json`. |
| AC-5  | pass | Locked test passed; sandbox replay as in R-5. |
| AC-6  | pass | Locked test passed (`409 Request in progress` comes from the existing idempotency interceptor/executor). |
| AC-7  | pass | Locked test passed; the lock-conflict `409` mapping is generic (cause chain error codes 5/6), not special-cased to the test; `SqliteErrorsTest` covers it, and non-lock DB errors stay `500`. |
| AC-8  | pass | Locked tests (a)–(k) and the boundary test passed; sandbox: `"0.00"` → `400`, JSON number `50` → `400`. |
| AC-9  | pass | Locked test passed; sandbox: `404` `User not found`. |
| AC-10 | pass | Locked test passed (existing `IdempotencyInterceptor`). |
| AC-11 | pass | Locked test passed; the same invariant SQL run against the sandbox file DB after 32 deposits returned no violations. |
| AC-12 | pass | Locked test passed. |

**Build:** `python3 scripts/app.py build` (`gradlew.bat build`): `BUILD SUCCESSFUL`, 178 tests, 0 failures, 0 errors,
0 skipped. `TEST-...Story004DepositMoneyAcceptanceTest.xml`: `tests="23" skipped="0" failures="0" errors="0"`, so
all 23 locked tests ran. The implementer's tests (`LedgerServiceTest` 5, `TransactionResponseTest` 1,
`SqliteErrorsTest` 4, `DepositApiTest` 5) are meaningful, and none of them changes balances outside the ledger.

**Locked tests verify:**

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story004DepositMoneyAcceptanceTest.kt
STORY-004-deposit-money-with-double-entry-ledger: 1/1 locked tests unchanged
```

The locked tests of STORY-001, 002, 003 and 009 also verify `OK` (1/1 each). There are no `@Disabled`, assumptions or
tags in `src/test`, and `build.gradle.kts` and `src/test/resources` are unchanged (`git diff HEAD` empty).

**Conventions and scope:** the Flyway migration `V3__create_ledger.sql` matches *Technical details* (STRICT tables,
FKs, indexes); V1/V2 are unchanged. DTOs are separate from entities, there is no `!!`, the code is blocking, all text is
English, and `AGENTS.md` is updated (new error title, lock-conflict `409`, ledger rule). The cross-cutting
`ApiExceptionHandler` change is required by *Constraints and notes* (lock conflicts must be `409`, never `500`).
Nothing from *Out of scope* was implemented. The evidence in Attempt 1 matches what I observed (files, test counts,
verify output).

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| F-1 | minor | `src/test/kotlin/com/example/kotlintest/acceptance/Story004DepositMoneyAcceptanceTest.kt:513` | The new locked test adds a compiler warning (`Unnecessary safe call on a non-null receiver of type 'ByteArray'`; the same pattern exists in the Story003/Story009 locked tests). The implementer can't change locked tests, so this doesn't block. It should be cleaned up by a future story's test-writer when these files are touched again. |

Severity: `blocking` (must be fixed before approval) or `minor` (should be fixed, does not block approval).
