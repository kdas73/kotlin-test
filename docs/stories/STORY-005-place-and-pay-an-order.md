---
id: STORY-005
title: Place and pay an order
status: Completed
created: 2026-11-08
updated: 2026-11-09
depends_on: [STORY-003, STORY-004]
related: []
---

# STORY-005: Place and pay an order

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |
| 2026-11-09 | Pending | Test Writing | test-writer | Writing acceptance tests |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 1: 28 acceptance tests, red for the expected reasons |
| 2026-11-09 | Test Review | Ready | test-reviewer | Tests locked |
| 2026-11-09 | Ready | In Progress | implementer | Implementation started |
| 2026-11-09 | In Progress | In Review | implementer | Attempt 1: build green, 28 locked tests pass, lock verified |
| 2026-11-09 | In Review | Completed | reviewer | Review 1: Approved (2 minor findings: F-1, F-2) |

## Context

A user buys a good by placing an **order**. An order is for one good in some quantity, and **placing it pays for it**:
in one atomic step the system checks stock and balance, decreases the stock, debits the user's account (an
`ORDER_PAYMENT` double-entry transaction from the user's account to the `REVENUE` account), and records the order as
`PAID`.

This is the operation where **money must never be taken twice**. Protection has several layers: the `Idempotency-Key`
mechanism (a resent request is replayed, not repeated), atomic database transactions with optimistic locking (concurrent
orders can't overdraw an account or oversell stock), and database constraints (one payment per order, no negative user
balance or stock).

## Requirements

- **R-1:** A client can place an order for a `quantity` (`1..10000`) of one good on behalf of a user.
- **R-2:** Placing an order is atomic. On success, all of these happen in one database transaction: the good's stock
  decreases by `quantity`; an `ORDER_PAYMENT` transaction of `quantity × price` moves money from the user's account to
  the `REVENUE` account; an order with status `PAID` is stored. On any failure none of them happen.
- **R-3:** The order stores the good's price at the time of the order (`unitPrice`) and `totalAmount = quantity ×
  unitPrice`. Later price changes don't affect existing orders. In the API, `unitPrice`, `totalAmount` and transaction
  amounts are money strings (STORY-001 *Money format*); the database stores them as integer minor units.
- **R-4:** If the stock is lower than `quantity`, the order is rejected with `409 Insufficient stock`. If the user's
  balance is lower than `totalAmount`, it is rejected with `409 Insufficient funds`. If both apply, `Insufficient stock`
  is returned. A user's balance and a good's stock can never become negative.
- **R-5:** Placing an order requires an `Idempotency-Key` and follows the idempotency rules of STORY-001. A repeated
  request with the same key never debits the account or decreases the stock again.
- **R-6:** Concurrent orders (different keys) that together exceed the balance or the stock never overdraw the account or
  oversell the good: only as many succeed as the balance and stock allow, and every other one fails with `409` and no
  effect.
- **R-7:** There is at most one `ORDER_PAYMENT` transaction per order, enforced by the database.
- **R-8:** An unknown user or good returns `404` and changes nothing. Invalid input returns `400` and changes nothing.
- **R-9:** A client can read an order by ID (`404` if unknown) and list orders with pagination, optionally filtered by
  user.
- **R-10:** A transaction response (`GET /api/v1/transactions/{id}`, STORY-004) includes the `orderId` it belongs to
  (`null` for deposits).

## Out of scope

- Orders with several goods (line items), carts, shipping, discounts.
- Cancelling orders and refunds (STORY-006).
- Unpaid / pending orders; payment from any other source than the user's account.

## Technical details

### API

| Method | Path                    | Request body                  | Success response              | Error responses            |
|--------|-------------------------|-------------------------------|-------------------------------|----------------------------|
| `POST` | `/api/v1/orders`        | `{userId, goodId, quantity}`  | `201` + `Location` + order    | `400`, `404`, `409`, `422` |
| `GET`  | `/api/v1/orders/{id}`   | —                             | `200` + order                 | `404`                      |
| `GET`  | `/api/v1/orders`        | — (query `userId` optional)   | `200` + page of orders        | `400`                      |

**Place order request** (`Content-Type: application/json`, header `Idempotency-Key` required):

```json
{ "userId": 1, "goodId": 2, "quantity": 3 }
```

**Order response** (`POST` 201, `GET` 200). `Location` path ends with `/api/v1/orders/{id}`:

```json
{
  "id": 5,
  "userId": 1,
  "goodId": 2,
  "quantity": 3,
  "unitPrice": "15.00",
  "totalAmount": "45.00",
  "status": "PAID",
  "paymentTransactionId": 12,
  "refundTransactionId": null,
  "createdAt": "2026-11-08T12:00:00.123Z",
  "updatedAt": "2026-11-08T12:00:00.123Z"
}
```

`refundTransactionId` is always `null` in this story; STORY-006 fills it.

**Transaction response** (STORY-004) gets one more field, `orderId`:

```json
{ "id": 12, "type": "ORDER_PAYMENT", "amount": "45.00", "orderId": 5, "createdAt": "...",
  "entries": [ { "accountId": 7, "amount": "-45.00" }, { "accountId": 2, "amount": "45.00" } ] }
```

In the database this order has `unit_price = 1500`, `total_amount = 4500`, and the payment transaction has
`amount = 4500` with entries `-4500` / `4500` (minor units).

**Validation** (all → `400`): `userId`, `goodId` required positive integers; `quantity` required, `1..10000`;
malformed JSON.

**Processing order:** idempotency header checks → validation (`400`) → user exists (`404`) → good exists (`404`) →
stock (`409 Insufficient stock`) → balance (`409 Insufficient funds`) → write.

**Listing:** `GET /api/v1/orders?userId={id}&page&size&sort`. Pagination per the project standard (STORY-001). Allowed
sort properties: `id`, `createdAt`, `totalAmount`; default `id,asc`. Without `userId` all orders are listed; an unknown
`userId` gives an empty page.

**Errors** (`ProblemDetail`):

| Status | Title                     | When                                                         |
|--------|---------------------------|--------------------------------------------------------------|
| `404`  | (default)                 | unknown user, good or order                                  |
| `409`  | `Insufficient stock`      | good quantity < requested quantity                           |
| `409`  | `Insufficient funds`      | user balance < total amount                                  |
| `409`  | `Concurrent modification` | lost an optimistic-locking / `SQLITE_BUSY` race (no effect)  |
| idempotency errors as in STORY-001 |  |                                                              |

### Data model

Migration `V{n}__create_orders.sql`, where `n` is the next free Flyway version at implementation time:

```sql
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

ALTER TABLE transactions ADD COLUMN order_id INTEGER REFERENCES orders (id);

CREATE UNIQUE INDEX ux_transactions_order_type ON transactions (order_id, type) WHERE order_id IS NOT NULL;
```

- `paymentTransactionId` / `refundTransactionId` are read from `transactions` by `order_id` and `type`.
- `ux_transactions_order_type` guarantees at most one `ORDER_PAYMENT` (and one `ORDER_REFUND`) per order.
- Payment entries: user account `-totalAmount`, `REVENUE` account `+totalAmount`.

### Affected code

- `order/`: `Order.kt`, `OrderRepository.kt`, `OrderService.kt`, `OrderController.kt`, `OrderDtos.kt`.
- `ledger/`: `Transaction` gets `orderId`; the transaction response gets `orderId`; `LedgerService` reused for the
  payment.
- `good/`: stock decrease via the `Good` aggregate (`@Version`).
- `common/`: map the new domain exceptions (`Insufficient stock`, `Insufficient funds`).

### Constraints and notes

- One short database transaction per order: no remote calls, no waiting inside it.
- The stock and balance checks and the writes must not be split across transactions. Optimistic locking (`@Version` on
  `accounts` and `goods`) or SQLite's single writer must make a concurrent loser fail with `409` and no effect, never
  `500` and never a negative balance or stock. A retry inside the service is allowed.
- `quantity ≤ 10000` and `price ≤ "10000000.00"` (`1000000000` minor units) keep `total_amount ≤ 10^13` minor units,
  which fits in `Long` and SQLite `INTEGER`. Compute `total_amount` in minor units, never with floating point.

## Acceptance criteria

Tests share one database: system-account balances are checked as differences, and every test uses its own users,
goods and keys. Balances are set up through the deposit API (STORY-004) and goods through the goods API (STORY-003), so
the ledger invariants hold. API money values are strings in major units; database values are integers in minor units
(`"15.00"` ↔ `1500`). "A user with balance `"100.00"`" means a new user who deposited `"100.00"`.

- **AC-1:** Given a user with balance `"100.00"` and a good with `price` `"15.00"` and `quantity` `10`, when the client
  sends `POST /api/v1/orders` with a new key and `{"userId":<u>,"goodId":<g>,"quantity":3}`, then the response is `201`,
  the `Location` path ends with `/api/v1/orders/{id}`, and the body has `userId`, `goodId`, `quantity` `3`, `unitPrice`
  `"15.00"`, `totalAmount` `"45.00"` (both JSON strings), `status` `"PAID"`, a non-null `paymentTransactionId`,
  `refundTransactionId` `null`, and `createdAt`/`updatedAt` ending in `Z`. `GET /api/v1/users/{u}/account` returns
  `balance` `"55.00"`. In the database: the good has `quantity = 7`; the user account has `balance = 5500`; the
  `REVENUE` balance increased by exactly `4500`; `orders` has the row with `status = 'PAID'`, `unit_price = 1500`,
  `total_amount = 4500`; `transactions` has exactly one row with `order_id` = the order ID, `type = 'ORDER_PAYMENT'`,
  `amount = 4500` and `id` = `paymentTransactionId`, with exactly 2 entries: the user account `-4500` and the `REVENUE`
  account `4500`.
- **AC-2:** Given the order from AC-1, when the client sends `GET /api/v1/transactions/{paymentTransactionId}`, then the
  response is `200` with `type` `"ORDER_PAYMENT"`, `amount` `"45.00"`, `orderId` = the order ID, and entries with
  `amount` `"-45.00"` (user account) and `"45.00"` (`REVENUE`). The transaction of a deposit has `orderId` `null`.
- **AC-3:** Given an order placed for a good with `price` `"15.00"`, when the client changes the good's price to
  `"20.00"` (`PATCH /api/v1/goods/{id}`) and then sends `GET /api/v1/orders/{orderId}`, then the response is `200` with
  `unitPrice` `"15.00"` and `totalAmount` unchanged.
- **AC-4:** Given a user with balance `"10.00"` and a good with `price` `"15.00"` and `quantity` `5`, when the client
  orders `quantity` `1`, then the response is `409` with title `Insufficient funds`, and the account row keeps
  `balance = 1000`, the stock, the number of rows in `orders` and the number of rows in `transactions` are unchanged.
- **AC-5:** Given a user with balance `"1000.00"` and a good with `price` `"1.00"` and `quantity` `2`, when the client
  orders `quantity` `3`, then the response is `409` with title `Insufficient stock`, and the balance, the stock and the
  number of rows in `orders` and `transactions` are unchanged. Given a new user with balance `"0.00"` (no deposit) and
  the same good, when the client orders `quantity` `3`, then the title is also `Insufficient stock`.
- **AC-6:** Given a user with balance `"30.00"` and a good with `price` `"10.00"` and `quantity` `3`, when the client
  orders `quantity` `3`, then the response is `201` with `totalAmount` `"30.00"`, the account returns `balance`
  `"0.00"` (row `balance = 0`) and the stock is `0`.
- **AC-7:** Given an order with key `K` returned `201`, when the client sends exactly the same request with key `K`
  again, then the response is `201` with `Idempotent-Replayed: true`, the same `Location` and a JSON-equal body; the
  balance was debited once, the stock was decreased once, `orders` has exactly one order for that user, and the user
  account has exactly one `ORDER_PAYMENT` entry.
- **AC-8:** Given a user with balance `"100.00"`, a good with `price` `"10.00"` and `quantity` `10`, and a new key `K`,
  when the client sends 10 identical order requests for `quantity` `1` with key `K` concurrently, then exactly one order
  exists for that user, the account row has `balance = 9000`, the stock is `9`, every response is `201` (JSON-equal
  bodies) or `409` with title `Request in progress`, and at least one is `201`.
- **AC-9:** Given a user with balance `"10.00"` and a good with `price` `"7.00"` and `quantity` `10`, when the client
  sends 5 orders for `quantity` `1` concurrently, each with its own new key, then exactly one response is `201`, all
  others are `409`, the account row has `balance = 300`, the stock is `9`, and exactly one order exists for that user.
- **AC-10:** Given a good with `price` `"1.00"` and `quantity` `1` and two users each with balance `"10.00"`, when both
  users order `quantity` `1` concurrently (different keys), then exactly one response is `201`, the other is `409`, the
  stock is `0`, and exactly one of the two users was debited (one account row has `balance = 900`, the other
  `balance = 1000`).
- **AC-11:** Given an existing good and no user with a given ID, when the client orders for that user, then the response
  is `404`; given an existing user and no good with a given ID, when the client orders that good, then the response is
  `404`. In both cases the number of rows in `orders` and `transactions` is unchanged.
- **AC-12:** Given a user and a good, when the client sends an order with (a) no `userId`, (b) no `goodId`, (c) no
  `quantity`, (d) `quantity` `0`, (e) `quantity` `-1`, (f) `quantity` `10001`, or (g) malformed JSON, each with a new key,
  then each response is `400` with `Content-Type: application/problem+json` and nothing changes. A valid order without an
  `Idempotency-Key` returns `400` with title `Idempotency-Key header required` and nothing changes.
- **AC-13:** Given an order, when the client sends `GET /api/v1/orders/{id}`, then the response is `200` with a body
  JSON-equal to the creation response. For an unknown ID the response is `404`.
- **AC-14:** Given user A with 2 orders and user B with 1 order, when the client sends
  `GET /api/v1/orders?userId=<A>&sort=id,asc`, then `content` has exactly A's 2 orders in ascending `id` order and
  `page.totalElements` is `2`; with `size=1`, `content` has 1 order and `page.totalPages` is `2`. For a user ID that does
  not exist, the response is `200` with empty `content` and `page.totalElements` `0`.
- **AC-15:** Given an order with its `ORDER_PAYMENT` transaction, when a test inserts a second `transactions` row with the
  same `order_id` and `type = 'ORDER_PAYMENT'` via SQL, then the insert fails with a constraint violation. When a test
  sets a user account's `balance` to `-1` via SQL, then the update fails with a constraint violation.
- **AC-16:** Given the orders of the tests above, then for every row in `transactions` there are exactly 2 entries that
  sum to `0`, and for every account `balance = COALESCE(SUM(entries), 0)`; no user account has a negative balance and no
  good has a negative quantity.

- **AC-17:** Given a user with balance `"10.00"` and a good with `price` `"0.57"` and `quantity` `10`, when the client
  orders `quantity` `3`, then the response has `unitPrice` `"0.57"` and `totalAmount` `"1.71"` exactly, the account
  returns `balance` `"8.29"`, and in the database `unit_price = 57`, `total_amount = 171`, the user account
  `balance = 829`, and `typeof(unit_price)` and `typeof(total_amount)` are `'integer'`. Given a user with balance
  `"10000000.00"` and a good with `price` `"9999999.99"` and `quantity` `1`, when the client orders `quantity` `1`, then
  `totalAmount` is `"9999999.99"` and the balance is `"0.01"`.

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
- `src/test/kotlin/com/example/kotlintest/acceptance/Story005PlaceOrderAcceptanceTest.kt`

**AC coverage:**

| AC    | Test (class › method)                                                                                                  |
|-------|------------------------------------------------------------------------------------------------------------------------|
| AC-1  | `Story005PlaceOrderAcceptanceTest` › `AC-1 places an order and returns 201 with Location, the order, and the ledger and stock changes` |
| AC-2  | `Story005PlaceOrderAcceptanceTest` › `AC-2 the payment transaction has orderId and the order entries, a deposit transaction has orderId null` |
| AC-3  | `Story005PlaceOrderAcceptanceTest` › `AC-3 a later price change does not change the unit price and total of an existing order` |
| AC-4  | `Story005PlaceOrderAcceptanceTest` › `AC-4 rejects an order the balance does not cover with 409 Insufficient funds and changes nothing` |
| AC-5  | `Story005PlaceOrderAcceptanceTest` › `AC-5 rejects an order larger than the stock with 409 Insufficient stock and changes nothing`; `AC-5 returns Insufficient stock when both the stock and the balance are too low` |
| AC-6  | `Story005PlaceOrderAcceptanceTest` › `AC-6 an order that uses the whole balance and the whole stock succeeds`           |
| AC-7  | `Story005PlaceOrderAcceptanceTest` › `AC-7 a repeated order with the same key is replayed and pays once`                |
| AC-8  | `Story005PlaceOrderAcceptanceTest` › `AC-8 concurrent identical orders with the same key pay once`                      |
| AC-9  | `Story005PlaceOrderAcceptanceTest` › `AC-9 concurrent orders with different keys never overdraw the account`            |
| AC-10 | `Story005PlaceOrderAcceptanceTest` › `AC-10 concurrent orders of two users never oversell the last item`                |
| AC-11 | `Story005PlaceOrderAcceptanceTest` › `AC-11 an order for an unknown user returns 404 and changes nothing`; `AC-11 an order for an unknown good returns 404 and changes nothing` |
| AC-12 | `Story005PlaceOrderAcceptanceTest` › `AC-12 (a)` … `AC-12 (g)` (one test per invalid body); `AC-12 rejects a valid order without Idempotency-Key with 400 and changes nothing` |
| AC-13 | `Story005PlaceOrderAcceptanceTest` › `AC-13 reads an order by id and returns 404 problem detail for an unknown id`      |
| AC-14 | `Story005PlaceOrderAcceptanceTest` › `AC-14 lists the orders of one user with pagination`                               |
| AC-15 | `Story005PlaceOrderAcceptanceTest` › `AC-15 the database rejects a second ORDER_PAYMENT transaction for the same order`; `AC-15 the database rejects a negative user account balance` |
| AC-16 | `Story005PlaceOrderAcceptanceTest` › `AC-16 every transaction is balanced, every balance equals its entries, nothing is negative` |
| AC-17 | `Story005PlaceOrderAcceptanceTest` › `AC-17 computes the total exactly in integer minor units`; `AC-17 handles the largest price exactly` |

**Red run:** `python3 scripts/app.py test` (`./gradlew test`): compiles; 206 tests, 28 failures — all 28 are the new
STORY-005 tests; all 178 pre-existing tests pass.

| Test | Failure reason (expected before implementation) |
|------|--------------------------------------------------|
| AC-1, AC-2, AC-3, AC-6, AC-7, AC-8, AC-13, both AC-17 | `POST /api/v1/orders` returns `404 Not Found` ("No static resource api/v1/orders"): endpoint does not exist yet |
| AC-9, AC-10 | all concurrent `POST /api/v1/orders` responses are `404` (0 × `201`): endpoint does not exist yet |
| AC-14, both AC-15, AC-16 | setup order via `POST /api/v1/orders` returns `404`: endpoint does not exist yet |
| AC-4, both AC-5, both AC-11, AC-12 (a)–(g), AC-12 without key | `SELECT count(*) FROM orders` fails with `SQLITE_ERROR` (no such table `orders`): table does not exist yet |

**Notes:**
- **Data setup.** Nothing is deleted (the in-memory DB is shared by all test classes, as in STORY-004). Every test
  registers its own users (STORY-001 API), sets balances through the deposit API (STORY-004), creates its own goods
  (STORY-003 API) and uses new UUID idempotency keys, so the ledger invariants hold. "A user with balance `"0.00"`" is a
  user without a deposit. The `REVENUE` balance is checked as a before/after difference.
- **"Nothing changes"** (AC-4, AC-5, AC-11, AC-12) is checked as: user account `balance`, the good's `quantity`, and the
  row counts of `orders`, `transactions` and `transaction_entries` are equal before and after. The AC-12 user is funded
  (`"100.00"`) and the good is in stock, so only the invalid input can be the reason for the rejection.
- **AC-5, second part:** uses a new good with the same `price` `"1.00"` / `quantity` `2` instead of reusing the first
  test's good; the first scenario must leave that good unchanged anyway.
- **AC-9 / AC-10:** the non-`201` responses must be `409` problem details; the title is not asserted, because the AC
  allows any `409` (`Insufficient funds`, `Insufficient stock` or `Concurrent modification`). AC-8 asserts the title
  `Request in progress` for the non-`201` responses, as the AC states.
- **Unknown IDs** (AC-11, AC-13, AC-14) are `MAX(id) + 1000000` of the table, asserted not to exist; this fits any
  integer type the implementer chooses for IDs.
- **AC-15:** each statement runs in a `TransactionTemplate` transaction that is always rolled back, so a missing
  constraint cannot pollute the shared database (and the ledger checks of other tests). A "constraint violation" means a
  `java.sql.SQLException` in the cause chain with primary result code `SQLITE_CONSTRAINT` (19) or a
  "constraint failed" message. The `INSERT` of the second `ORDER_PAYMENT` is otherwise valid (`amount > 0`, valid type),
  so only `ux_transactions_order_type` can reject it. The negative-balance `CHECK` already exists since STORY-001; that
  test targets the account of a user who placed an order (the AC's "Given an order"), which is why it is red now. Its
  detection logic was verified with a throwaway probe test (deleted) against the existing `CHECK`: the violation is
  recognised, a rolled-back successful statement leaves no trace, and "no such column" is not mistaken for a constraint.
- **AC-16** places its own orders first (it does not depend on other tests running before it), then checks the whole
  database: every transaction has exactly 2 entries summing to `0`, every account `balance = COALESCE(SUM(entries), 0)`,
  no `USER` account with `balance < 0`, no good with `quantity < 0`.
- **AC-2:** entries are expected in STORY-004 order (`amount` ascending): user account `"-45.00"`, then `REVENUE`
  `"45.00"`. `orderId` must be present and JSON `null` for a deposit transaction.
- **AC-14:** asserts that `content` is exactly A's two order IDs in ascending order (and each has `userId` = A),
  `totalElements` `2`; with `size=1`, one item and `totalPages` `2`; an unknown `userId` gives `200`, empty `content`,
  `totalElements` `0`.
- Existing STORY-004 test `AC-4` compares `GET /api/v1/transactions/{id}` with the deposit `POST` response; both will
  gain `orderId: null`, so it stays valid.

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
| AC-1  | yes     | yes | `201`, `Location` path suffix, integer `id`, `userId`/`goodId`/`quantity`, `unitPrice`/`totalAmount` as JSON strings, `status` `PAID`, integer `paymentTransactionId`, `refundTransactionId` present and `null`, timestamps ending in `Z`; account API `"55.00"` + row `5500`; stock `7`; `REVENUE` delta exactly `4500`; `orders` row (`PAID`, `1500`, `4500`); exactly one `transactions` row for the order (`ORDER_PAYMENT`, `4500`, id = `paymentTransactionId`) with entries user `-4500` / `REVENUE` `4500`. |
| AC-2  | yes     | yes | `type`, `amount` string, integer `orderId` = order ID, entries in the STORY-004 documented order (negative first) with account IDs; deposit transaction has `orderId` present and `null`. |
| AC-3  | yes     | yes | `PATCH` price to `"20.00"` asserted `200`, then `GET` shows `unitPrice` `"15.00"` and the original `totalAmount`. |
| AC-4  | yes     | yes | `409` problem `Insufficient funds`; balance `1000`, stock `5`, row counts of `orders`, `transactions` (and `transaction_entries`) unchanged. |
| AC-5  | yes     | yes | Two tests: funded user → `Insufficient stock`, nothing changed; user without deposit → `Insufficient stock` (uses a new good with the same price/stock instead of the same good; equivalent). |
| AC-6  | yes     | yes | `totalAmount` `"30.00"`, account API `"0.00"` + row `0`, stock `0`. |
| AC-7  | yes     | yes | Replay `201` with `Idempotent-Replayed: true`, same `Location`, JSON-equal body; balance `5500`, stock `7`, one order for the user, one `ORDER_PAYMENT` entry on the user account. |
| AC-8  | yes     | yes | 10 concurrent same-key requests; non-`201` must be `409` `Request in progress`; ≥ 1 `201`, all `201` bodies JSON-equal; one order, balance `9000`, stock `9`. |
| AC-9  | yes     | yes | 5 concurrent different keys; exactly one `201`, all others `409` problem; balance `300`, stock `9`, one order. |
| AC-10 | yes     | yes | Two users race for the last item; exactly one `201`, other `409`; stock `0`; balances `{900, 1000}`. |
| AC-11 | yes     | yes | Unknown user and unknown good (IDs asserted absent) → `404` problem; row counts unchanged, stock / balance unchanged. |
| AC-12 | yes     | yes | (a)–(g) one test each with a funded user and in-stock good, so only the bad input can cause the rejection: `400` `application/problem+json`, nothing changes; missing key → `400` `Idempotency-Key header required`, nothing changes. |
| AC-13 | yes     | yes | `GET` body JSON-equal to the creation body; unknown ID → `404` problem. |
| AC-14 | yes     | yes | A's two order IDs exactly, ascending, each with `userId` = A, `totalElements` `2`; `size=1` → 1 item, `totalPages` `2`; unknown `userId` → `200`, empty `content`, `totalElements` `0`. |
| AC-15 | yes     | yes | Second `ORDER_PAYMENT` insert for an existing order and `balance = -1` update both run in an always-rolled-back transaction and must fail with `SQLITE_CONSTRAINT`. Verified against `V3__create_ledger.sql`: the insert is otherwise valid (allowed type, `amount > 0`, `created_at` set, `order_id` references an existing order). |
| AC-16 | yes     | yes | Places its own orders, then checks the whole DB: every transaction has exactly 2 entries summing to `0`, every account `balance = COALESCE(SUM(entries), 0)`, no negative `USER` balance, no negative stock. Order-independent. |
| AC-17 | yes     | yes | `"0.57"` × 3 → `"1.71"`, balance `"8.29"` / `829`, rows `57` / `171` with `typeof` `'integer'`; largest price `"9999999.99"` with a `"10000000.00"` deposit (the STORY-004 maximum) → total `"9999999.99"`, balance `"0.01"` / `1`. |

**Quality checks:** black-box only (no production imports; only `RestTestClient`, `JdbcTemplate` and a
`TransactionTemplate` for the rolled-back constraint probes): yes · deterministic (no sleeps or clock dependence;
concurrency via a start latch, outcomes fixed by balance/stock): yes · isolated test data (own users, goods, keys;
`REVENUE` checked as a delta; nothing deleted): yes · AC-prefixed English names: yes · no `@Disabled`, tags or
assumptions: yes · no changes to `build.gradle.kts` or `src/test/resources` (`git status`: only the story and the new
test file): yes · no over-specification: yes · red for the expected reason (own run): yes.

**Red run (own):** `python3 scripts/app.py test`: compiles; 206 tests, 28 failures, 0 errors, 0 skipped. All 28
failures are in `Story005PlaceOrderAcceptanceTest`: `POST /api/v1/orders` → `404` "No static resource api/v1/orders"
(AC-1, 2, 3, 6, 7, 8, 9, 10, 13, 14, 15, 16, 17) or `SELECT count(*) FROM orders` → `SQLITE_ERROR` no such table (AC-4,
5, 11, 12). All 178 other tests pass.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| T-1 | minor    | `Story005PlaceOrderAcceptanceTest.kt:702` | `assertConstraintViolation` accepts any `SQLITE_CONSTRAINT`, not specifically the `UNIQUE` violation of `ux_transactions_order_type`. Today the probe insert is otherwise valid, so only the unique index can reject it, but an implementation that added extra constraints to `transactions` could make the test pass without the index. The reviewer should confirm the migration creates `ux_transactions_order_type` as specified. |
| T-2 | minor    | `Story005PlaceOrderAcceptanceTest.kt:71-73` | `userId`, `goodId` and `quantity` in the order response are read with `asLong()`/`asInt()`, which would also accept numeric strings. The *Technical details* show them as JSON numbers; `id` and `paymentTransactionId` are checked as integers. Low risk. |

**Locked tests:**

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/Story005PlaceOrderAcceptanceTest.kt` | b9a9966de9e59acde977bf6c4b2a59c2d30d4c03bd93aa198f51f20036d02a4c |

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

**Summary:** Implemented placing and paying an order. `OrderService.place` runs inside the idempotent executor's
transaction (which is a write transaction from its first statement): it loads the user's account (`404`), the good
(`404`), checks the stock (`409 Insufficient stock`) and then the balance (`409 Insufficient funds`), takes the stock
out of the good (`GoodService.takeStock`, written with the good's `@Version`), stores the `PAID` order with the good's
current price, and posts an `ORDER_PAYMENT` transaction from the user's account to `REVENUE` through
`LedgerService.post` (both accounts written with their `@Version`) with the new `order_id`. The total is computed with
`Math.multiplyExact` on minor units. `GET /api/v1/orders/{id}` and `GET /api/v1/orders[?userId=]` (sort `id`,
`createdAt`, `totalAmount`) read orders; `paymentTransactionId` / `refundTransactionId` are looked up in `transactions`
by `order_id` and `type`. The transaction response gets `orderId`. Lost races surface as `409 Concurrent modification`
through the existing handler.

**Changed files:**
- `src/main/resources/db/migration/V4__create_orders.sql`: new; `orders` table, `transactions.order_id`, unique index `ux_transactions_order_type`
- `src/main/kotlin/com/example/kotlintest/order/Order.kt`: new; `Order` entity, `OrderStatus`, `OrderNotFoundException`, `InsufficientStockException`, `InsufficientFundsException`
- `src/main/kotlin/com/example/kotlintest/order/OrderRepository.kt`: new; paging repository with `findByUserId`
- `src/main/kotlin/com/example/kotlintest/order/OrderService.kt`: new; `place`, `get`, `list`, payment / refund transaction ID lookup
- `src/main/kotlin/com/example/kotlintest/order/OrderController.kt`: new; `POST` (idempotent), `GET` by ID, `GET` list
- `src/main/kotlin/com/example/kotlintest/order/OrderDtos.kt`: new; `PlaceOrderRequest` (validation, integer-only `quantity`), `OrderResponse`
- `src/main/kotlin/com/example/kotlintest/ledger/Transaction.kt`: `orderId` property
- `src/main/kotlin/com/example/kotlintest/ledger/TransactionDtos.kt`: `orderId` in `TransactionResponse`
- `src/main/kotlin/com/example/kotlintest/ledger/LedgerService.kt`: `post(..., orderId)` and `findTransactionId`
- `src/main/kotlin/com/example/kotlintest/ledger/TransactionRepository.kt`: `findIdByOrderIdAndType` query
- `src/main/kotlin/com/example/kotlintest/good/GoodService.kt`: `takeStock`
- `src/test/kotlin/com/example/kotlintest/order/OrderServiceTest.kt`: new; unit tests of the order logic (not locked)
- `src/test/kotlin/com/example/kotlintest/good/GoodServiceTest.kt`: two `takeStock` tests (not locked)
- `AGENTS.md`: new fixed error titles (`Order not found`, `Insufficient stock`, `Insufficient funds`); ledger note on `order_id`

**Migrations:** `V4__create_orders.sql`

**Acceptance criteria evidence:**

| AC    | Locked acceptance test (now passing)                                                                                   | Additional tests by the implementer |
|-------|------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| AC-1  | `Story005PlaceOrderAcceptanceTest` › `AC-1 places an order and returns 201 with Location, the order, and the ledger and stock changes` | `OrderServiceTest` › `place stores a paid order at the current price and pays it from the user account to REVENUE` |
| AC-2  | `Story005PlaceOrderAcceptanceTest` › `AC-2 the payment transaction has orderId and the order entries, a deposit transaction has orderId null` | — |
| AC-3  | `Story005PlaceOrderAcceptanceTest` › `AC-3 a later price change does not change the unit price and total of an existing order` | — |
| AC-4  | `Story005PlaceOrderAcceptanceTest` › `AC-4 rejects an order the balance does not cover with 409 Insufficient funds and changes nothing` | `OrderServiceTest` › `place rejects an order the balance does not cover without writing` |
| AC-5  | `Story005PlaceOrderAcceptanceTest` › `AC-5 rejects an order larger than the stock with 409 Insufficient stock and changes nothing`; `AC-5 returns Insufficient stock when both the stock and the balance are too low` | `OrderServiceTest` › `place rejects an order larger than the stock before checking the balance` |
| AC-6  | `Story005PlaceOrderAcceptanceTest` › `AC-6 an order that uses the whole balance and the whole stock succeeds`           | `OrderServiceTest` › `place accepts an order that uses the whole stock and the whole balance` |
| AC-7  | `Story005PlaceOrderAcceptanceTest` › `AC-7 a repeated order with the same key is replayed and pays once`                | — |
| AC-8  | `Story005PlaceOrderAcceptanceTest` › `AC-8 concurrent identical orders with the same key pay once`                      | — |
| AC-9  | `Story005PlaceOrderAcceptanceTest` › `AC-9 concurrent orders with different keys never overdraw the account`            | — |
| AC-10 | `Story005PlaceOrderAcceptanceTest` › `AC-10 concurrent orders of two users never oversell the last item`                | — |
| AC-11 | `Story005PlaceOrderAcceptanceTest` › `AC-11 an order for an unknown user returns 404 and changes nothing`; `AC-11 an order for an unknown good returns 404 and changes nothing` | `OrderServiceTest` › `place reports an unknown user before an unknown good`, `place reports an unknown good` |
| AC-12 | `Story005PlaceOrderAcceptanceTest` › `AC-12 (a)` … `AC-12 (g)`; `AC-12 rejects a valid order without Idempotency-Key with 400 and changes nothing` | — |
| AC-13 | `Story005PlaceOrderAcceptanceTest` › `AC-13 reads an order by id and returns 404 problem detail for an unknown id`      | — |
| AC-14 | `Story005PlaceOrderAcceptanceTest` › `AC-14 lists the orders of one user with pagination`                               | — |
| AC-15 | `Story005PlaceOrderAcceptanceTest` › `AC-15 the database rejects a second ORDER_PAYMENT transaction for the same order`; `AC-15 the database rejects a negative user account balance` | — |
| AC-16 | `Story005PlaceOrderAcceptanceTest` › `AC-16 every transaction is balanced, every balance equals its entries, nothing is negative` | — |
| AC-17 | `Story005PlaceOrderAcceptanceTest` › `AC-17 computes the total exactly in integer minor units`; `AC-17 handles the largest price exactly` | — |

**Build:** `python3 scripts/app.py build` (`gradlew.bat build`):

```
> Task :test
> Task :check
> Task :build

BUILD SUCCESSFUL in 16s

=== Test summary ===
  com.example.kotlintest.acceptance.Story005PlaceOrderAcceptanceTest: 28 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.order.OrderServiceTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.good.GoodServiceTest: 7 tests, 0 failures, 0 errors, 0 skipped
  ...
  TOTAL: 214 tests, 0 failures, 0 errors, 0 skipped

build: SUCCESSFUL
```

The STORY-005 acceptance tests (concurrency ones included) were also run three more times with `--rerun`: 28 tests, 0 failures each time.

**Locked tests verify:** `python3 scripts/locked-tests.py verify STORY-005`:

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story005PlaceOrderAcceptanceTest.kt
STORY-005-place-and-pay-an-order: 1/1 locked tests unchanged
```

**Deviations and notes:**
- No test defects.
- The only Kotlin compiler warnings are `Unnecessary safe call on a non-null receiver of type 'ByteArray'` in locked acceptance
  tests (`Story005...AcceptanceTest.kt:812`, and the same pattern in the locked tests of STORY-003, 004 and 009). They are
  not in production or implementer code, and locked files can't be changed.
- Test review T-1: the migration creates `ux_transactions_order_type` exactly as specified (`UNIQUE INDEX ... (order_id, type) WHERE order_id IS NOT NULL`).
- `userId` / `goodId` are plain `Long?` fields (positive, required); only `quantity` uses the integer-only deserializer
  (test review T-2 noted this as low risk).
- `GoodService.takeStock` also sets the good's `updatedAt`, because the stock changed.
- `paymentTransactionId` / `refundTransactionId` cost one small query each per order; a page is capped at 100 orders.
- `AGENTS.md` was updated with the new fixed error titles and the ledger `order_id` note (definition of done, step 6).

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
| R-1 | yes | `PlaceOrderRequest` (`quantity` 1..10000, integer-only; `userId`/`goodId` required positive); `POST /api/v1/orders` in `OrderController`. |
| R-2 | yes | `OrderService.place` runs in the idempotent executor's single transaction: `GoodService.takeStock`, `orders.save` (`PAID`), `LedgerService.post(ORDER_PAYMENT, user → REVENUE, orderId)`. Any exception rolls back everything. |
| R-3 | yes | `unitPrice = good.price`, `totalAmount = Math.multiplyExact(price, quantity)` in minor units; DTO formats via `Money.format`; DB columns are `INTEGER` with `CHECK (total_amount = quantity * unit_price)`. |
| R-4 | yes | Stock is checked before balance (`InsufficientStockException` before `InsufficientFundsException`); both are `409` `ApiException`s with the fixed titles. Non-negative balance (existing `CHECK`) and stock (`takeStock` precondition + `@Version`). |
| R-5 | yes | The endpoint is wrapped in `IdempotentExecutor.execute`; replay, reuse and in-progress rules are inherited. |
| R-6 | yes | Good and both accounts are read and written with `@Version`, inside a transaction that is a write transaction from its first statement (idempotency record is inserted first). Losers get `409`. Confirmed by AC-8, AC-9, AC-10 passing. |
| R-7 | yes | `ux_transactions_order_type` is `UNIQUE (order_id, type) WHERE order_id IS NOT NULL` in `V4__create_orders.sql` (confirms test review T-1). |
| R-8 | yes | `UserNotFoundException` (user checked first) and `GoodNotFoundException` → `404`; bean validation / malformed JSON → `400`; nothing is written before the checks. |
| R-9 | yes | `GET /api/v1/orders/{id}` (`OrderNotFoundException` → `404`) and `GET /api/v1/orders[?userId=]` with `withValidatedSort(id, createdAt, totalAmount)` and `PageResponse`. Also exercised by hand in sandbox (`sort=totalAmount,desc`, `sort=createdAt,desc&size=1`, `sort=bogus` → `400 Invalid sort property`). |
| R-10 | yes | `Transaction.orderId` and `TransactionResponse.orderId` (`null` for deposits); the sandbox `GET /api/v1/transactions/2` returned `orderId: 1`. |

**Acceptance criteria:**

| AC    | Result | How checked |
|-------|--------|-------------|
| AC-1  | pass | Locked test passes (own build run). Sandbox: `201`, `Location: /api/v1/orders/1`, body fields as specified, balance `100.00 → 25.00` after two orders (30 + 45). |
| AC-2  | pass | Locked test passes; sandbox `GET /api/v1/transactions/2` → `ORDER_PAYMENT`, `30.00`, `orderId: 1`, entries `-30.00` / `30.00`. |
| AC-3  | pass | Locked test passes; the order stores `unit_price`, so a later price change cannot affect it (no join to `goods.price`). |
| AC-4  | pass | Locked test passes; sandbox `409 Insufficient funds` (nothing written). |
| AC-5  | pass | Both locked tests pass; code checks stock before balance. |
| AC-6  | pass | Locked test passes. |
| AC-7  | pass | Locked test passes; replay is handled by `IdempotencyInterceptor`. |
| AC-8  | pass | Locked test passes (28/28 in the full build). |
| AC-9  | pass | Locked test passes. |
| AC-10 | pass | Locked test passes. |
| AC-11 | pass | Both locked tests pass; `404` is raised before any write. |
| AC-12 | pass | All 8 locked tests pass; sandbox `"3"` and `3.5` for `quantity` → `400`. |
| AC-13 | pass | Locked test passes. |
| AC-14 | pass | Locked test passes; sandbox listing/paging/sort checked by hand. |
| AC-15 | pass | Both locked tests pass; the migration creates the unique index and the account `CHECK` already exists. |
| AC-16 | pass | Locked test passes. |
| AC-17 | pass | Both locked tests pass; arithmetic is `Math.multiplyExact` on `Long`, no floating point. |

**Build:** `python3 scripts/app.py build` (wraps `gradlew build`): BUILD SUCCESSFUL, 214 tests, 0 failures, 0 errors, 0
skipped. `Story005PlaceOrderAcceptanceTest`: 28 tests, 0 failures, 0 skipped; the acceptance tests of STORY-001, 002, 003,
004 and 009 also pass in full (26 / 14 / 38 / 23 / 5).

**Locked tests verify:** `python3 scripts/locked-tests.py verify STORY-005`:

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story005PlaceOrderAcceptanceTest.kt
STORY-005-place-and-pay-an-order: 1/1 locked tests unchanged
```

The locked tests of STORY-001, 002, 003, 004 and 009 also verify as `OK`. No `@Disabled`, assumptions or `@Tag` anywhere in
`src/test`; `build.gradle.kts` and `src/test/resources` are unchanged since the initial commit (not in `git status`);
the only modified existing test file is the non-locked `GoodServiceTest.kt` (two `takeStock` tests added).

**Conventions and scope:** Flyway migration `V4` is new and no applied migration was edited; the table is `STRICT`, snake_case,
with foreign keys. Money is `Long` minor units in code/DB and strings in JSON; no `!!`; blocking code only; DTOs are
separate from the entity; errors are `ApiException`s rendered by the single advice; `AGENTS.md` was updated with the new
fixed titles and the ledger note (definition of done, step 6). All text is in English. No changes outside the requirements
and nothing from *Out of scope* (no cancellation or refund logic; `refundTransactionId` is just a lookup that returns `null`).
The evidence in *Attempt 1* matches what I observed (files, test counts, verify output). The `Unnecessary safe call`
compiler warnings are in locked test code, as the implementer stated.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| F-1 | minor | `ledger/TransactionDtos.kt:8` | The edited KDoc line is far over the usual 120-character line length. Wrap it. |
| F-2 | minor | `order/OrderController.kt:48-51`, `order/OrderService.kt:82-87` | `toResponse` runs two extra queries per order, so a page of 100 orders costs 200 additional queries (N+1). Acceptable for this story (the implementer noted it) but worth replacing by one batched lookup by `order_id` if lists grow. |
