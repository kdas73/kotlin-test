---
id: STORY-004
title: Deposit money with a double-entry ledger
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: [STORY-001]
related: []
---

# STORY-004: Deposit money with a double-entry ledger

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

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
