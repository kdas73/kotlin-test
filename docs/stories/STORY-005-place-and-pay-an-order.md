---
id: STORY-005
title: Place and pay an order
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: [STORY-003, STORY-004]
related: []
---

# STORY-005: Place and pay an order

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

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
