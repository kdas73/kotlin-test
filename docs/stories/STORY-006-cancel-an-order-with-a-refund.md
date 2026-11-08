---
id: STORY-006
title: Cancel an order with a refund
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: [STORY-005]
related: []
---

# STORY-006: Cancel an order with a refund

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

## Context

A user may cancel a paid order (STORY-005). Cancelling refunds the money with a reversing double-entry transaction
(`ORDER_REFUND`, from the `REVENUE` account back to the user's account) and returns the goods to stock. The ledger is
append-only: the original payment stays, and the refund is a new transaction.

Cancellation is a `POST`, so it uses the `Idempotency-Key` mechanism. Just as money must never be taken twice, a refund
must never be paid twice: a repeated or concurrent cancellation of the same order refunds at most once.

## Requirements

- **R-1:** A client can cancel an order with status `PAID`.
- **R-2:** Cancelling is atomic. On success, all of these happen in one database transaction: the order status becomes
  `CANCELLED` and `updatedAt` is set; an `ORDER_REFUND` transaction of the order's `totalAmount`, linked to the order,
  moves money from the `REVENUE` account to the user's account; the good's stock increases by the order's `quantity`.
  On any failure none of them happen.
- **R-3:** The original `ORDER_PAYMENT` transaction is kept unchanged.
- **R-4:** Cancelling requires an `Idempotency-Key` and follows the idempotency rules of STORY-001. Repeating the request
  with the same key replays the original response and refunds nothing more.
- **R-5:** Cancelling an order that is already `CANCELLED` with a different key is rejected with
  `409 Order already cancelled` and changes nothing.
- **R-6:** Concurrent cancellations of the same order (with the same or different keys) refund at most once. There is at
  most one `ORDER_REFUND` transaction per order, enforced by the database (`ux_transactions_order_type`, STORY-005).
- **R-7:** Cancelling an unknown order returns `404` and changes nothing.

## Out of scope

- Partial cancellations or partial refunds.
- Time limits or other business rules for when an order can be cancelled.
- Re-opening a cancelled order.

## Technical details

### API

| Method | Path                                | Request body | Success response | Error responses            |
|--------|-------------------------------------|--------------|------------------|----------------------------|
| `POST` | `/api/v1/orders/{id}/cancellation`  | none (empty) | `200` + order    | `400`, `404`, `409`, `422` |

The request has no body (an empty body; its hash is the hash of the empty input for the idempotency fingerprint). Header
`Idempotency-Key` required. The response has no `Location` header.

**Response** `200`: the order in the STORY-005 shape, now with `status` `"CANCELLED"`, a non-null
`refundTransactionId`, the unchanged `paymentTransactionId`, and the new `updatedAt`:

```json
{
  "id": 5, "userId": 1, "goodId": 2, "quantity": 3, "unitPrice": "15.00", "totalAmount": "45.00",
  "status": "CANCELLED", "paymentTransactionId": 12, "refundTransactionId": 20,
  "createdAt": "2026-11-08T12:00:00.123Z", "updatedAt": "2026-11-08T12:05:00.456Z"
}
```

`GET /api/v1/orders/{id}` returns the same shape. `GET /api/v1/transactions/{refundTransactionId}` returns `type`
`"ORDER_REFUND"`, `amount` = `totalAmount` (for example `"45.00"`), `orderId` = the order ID, and entries
`REVENUE` `"-45.00"`, user account `"45.00"`. All money values are money strings (STORY-001 *Money format*); the
database stores them as integer minor units.

**Processing order:** idempotency header checks → order exists (`404`) → status is `PAID`
(else `409 Order already cancelled`) → write.

**Errors** (`ProblemDetail`):

| Status | Title                     | When                                                        |
|--------|---------------------------|-------------------------------------------------------------|
| `404`  | (default)                 | unknown order                                               |
| `409`  | `Order already cancelled` | order status is already `CANCELLED`                         |
| `409`  | `Concurrent modification` | lost an optimistic-locking / `SQLITE_BUSY` race (no effect) |
| idempotency errors as in STORY-001 |  |                                                             |

### Data model

No migration. Uses `orders`, `transactions` (`order_id`, `ux_transactions_order_type`), `transaction_entries`, `accounts`
and `goods`.

- Refund entries: `REVENUE` account `-totalAmount`, user account `+totalAmount`. The `REVENUE` balance is not
  constrained (it may go negative).
- The returned stock may exceed the API limit of `1000000` from STORY-003. That limit applies only to API input.

### Affected code

- `order/`: `OrderController.kt` (new endpoint), `OrderService.kt` (cancel logic), `Order` status transition.
- `ledger/`: `LedgerService` reused for the refund.
- `good/`: stock increase via the `Good` aggregate (`@Version`).
- `common/`: map `Order already cancelled`.

### Constraints and notes

- One short database transaction. `@Version` on `orders` ensures a concurrent second cancellation loses with `409`,
  never `500` and never a second refund.

## Acceptance criteria

Tests share one database: system-account balances are checked as differences, and every test uses its own users, goods
and keys. Data is set up through the APIs of STORY-001, STORY-003, STORY-004 and STORY-005. API money values are
strings in major units; database values are integers in minor units (`"45.00"` ↔ `4500`).

- **AC-1:** Given a user who deposited `"100.00"` and placed an order for `quantity` `3` of a good with `price`
  `"15.00"` (balance now `"55.00"`, stock now `7`), when the client sends `POST /api/v1/orders/{id}/cancellation` with a
  new key and no body, then the response is `200` with `status` `"CANCELLED"`, `unitPrice` `"15.00"`, `totalAmount`
  `"45.00"`, the original `paymentTransactionId`, a non-null `refundTransactionId` and `updatedAt` ending in `Z`, and
  `GET /api/v1/users/{u}/account` returns `balance` `"100.00"`. In the database: the order has
  `status = 'CANCELLED'`; the user account has `balance = 10000`; the stock is `10`; the `REVENUE` balance decreased by exactly `4500`; `transactions` has exactly
  one row with that `order_id` and `type = 'ORDER_REFUND'`, `amount = 4500` and `id` = `refundTransactionId`, with
  exactly 2 entries: `REVENUE` `-4500` and the user account `4500`.
- **AC-2:** Given the cancelled order from AC-1, then the `ORDER_PAYMENT` transaction of the order still exists with
  `amount = 4500` and its 2 entries; `GET /api/v1/orders/{id}` returns `200` with a body JSON-equal to the cancellation
  response; and `GET /api/v1/transactions/{refundTransactionId}` returns `type` `"ORDER_REFUND"`, `amount` `"45.00"`,
  `orderId` = the order ID, and entries with `amount` `"-45.00"` (`REVENUE`) and `"45.00"` (user account).
- **AC-3:** Given a cancellation with key `K` returned `200`, when the client sends the same cancellation with key `K`
  again, then the response is `200` with `Idempotent-Replayed: true` and a JSON-equal body, the user balance and the
  stock are the same as after the first cancellation, and there is still exactly one `ORDER_REFUND` transaction for the
  order.
- **AC-4:** Given a cancelled order, when the client sends a cancellation with a different new key, then the response is
  `409` with title `Order already cancelled`, the user balance and the stock are unchanged, and there is exactly one
  `ORDER_REFUND` transaction for the order.
- **AC-5:** Given a paid order and a new key `K`, when the client sends 10 cancellations of it with key `K`
  concurrently, then every response is `200` (JSON-equal bodies) or `409` with title `Request in progress`, at least one
  is `200`, the order is `CANCELLED`, the user was refunded exactly once, and there is exactly one `ORDER_REFUND`
  transaction for the order.
- **AC-6:** Given a paid order, when the client sends 5 cancellations of it concurrently, each with its own new key, then
  exactly one response is `200`, all others are `409`, the user was refunded exactly once, the stock was increased
  exactly once, and there is exactly one `ORDER_REFUND` transaction for the order.
- **AC-7:** Given no order with a given ID, when the client sends a cancellation for it with a new key, then the response
  is `404` with `Content-Type: application/problem+json` and the number of rows in `transactions` is unchanged. Given a
  paid order, when the client sends a cancellation without an `Idempotency-Key`, then the response is `400` with title
  `Idempotency-Key header required` and the order is still `PAID`.
- **AC-8:** Given a cancelled order, when a test inserts a second `transactions` row with the same `order_id` and
  `type = 'ORDER_REFUND'` via SQL, then the insert fails with a constraint violation.
- **AC-9:** Given the cancellations of the tests above, then for every row in `transactions` there are exactly 2 entries
  that sum to `0`, and for every account `balance = COALESCE(SUM(entries), 0)`.

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
