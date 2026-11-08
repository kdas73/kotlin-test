---
id: STORY-007
title: Show account statement
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: [STORY-005]
related: [STORY-004, STORY-006]
---

# STORY-007: Show account statement

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

## Context

Users need to see how their balance came about: a **statement** that lists every ledger entry on their account (deposits,
order payments and, once STORY-006 is done, refunds), newest first, with the signed amount for that account. It is
a read-only view of the double-entry ledger from STORY-004.

## Requirements

- **R-1:** A client can list the entries of a user's account as a statement, with pagination.
- **R-2:** Each statement item shows the transaction ID, the transaction type, the signed amount of this account's entry
  as a money string (negative = money left the account, positive = money entered), the order ID (`null` if none), and the transaction's
  creation time.
- **R-3:** Items are ordered newest first (by transaction ID, descending). The order is fixed.
- **R-4:** The statement contains only entries of that user's account, never entries of other accounts.
- **R-5:** The statement of an unknown user returns `404`.

## Out of scope

- Statements for system accounts (`FUNDING`, `REVENUE`).
- Filtering by date or type, a running balance per item, export formats.

## Technical details

### API

| Method | Path                                     | Request body | Success response        | Error responses |
|--------|------------------------------------------|--------------|-------------------------|-----------------|
| `GET`  | `/api/v1/users/{id}/account/transactions` | —           | `200` + page of items   | `404`           |

Query parameters: `page` (default `0`) and `size` (default `20`, capped at `100`), per the project standard from
STORY-001. The `sort` parameter is not supported and is ignored; the order is always transaction ID descending.

**Response** `200`:

```json
{
  "content": [
    { "transactionId": 12, "type": "ORDER_PAYMENT", "amount": "-45.00", "orderId": 5, "createdAt": "2026-11-08T12:05:00.000Z" },
    { "transactionId": 10, "type": "DEPOSIT", "amount": "50.00", "orderId": null, "createdAt": "2026-11-08T12:00:00.123Z" }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 2, "totalPages": 1 }
}
```

`amount` is `transaction_entries.amount` for the user's account, converted from minor units to a signed money string
(STORY-001 *Money format*): `-4500` → `"-45.00"`. `type`, `orderId` and `createdAt` come from the
`transactions` row.

**Errors** (`ProblemDetail`): `404` for an unknown user.

### Data model

No migration. Reads `accounts`, `transactions` and `transaction_entries`. The index
`ix_transaction_entries_account_id` (STORY-004) supports the query.

### Affected code

- `account/` or `ledger/`: `StatementController.kt` (or an endpoint in the existing account controller), a statement
  query (Spring Data JDBC `@Query` or `JdbcClient` with `LIMIT/OFFSET`), `StatementDtos.kt`.

### Constraints and notes

- Read-only; no transaction needed beyond a read. Never return an unbounded list.

## Acceptance criteria

Tests share one database, so every test uses its own users, goods and keys. Data is set up through the APIs of
STORY-001, STORY-003, STORY-004 and STORY-005.

- **AC-1:** Given a user who deposited `"50.00"` (transaction `D`) and then placed an order with `totalAmount` `"15.00"`
  (order `O`, payment transaction `P`), when the client sends `GET /api/v1/users/{id}/account/transactions`, then the
  response is `200`, `page.totalElements` is `2`, and `content` is
  `[{"transactionId":P,"type":"ORDER_PAYMENT","amount":"-15.00","orderId":O,...},{"transactionId":D,"type":"DEPOSIT","amount":"50.00","orderId":null,...}]`,
  where every `amount` is a JSON string and every item has `createdAt` ending in `Z`.
- **AC-2:** Given a new user without any transactions, when the client requests the statement, then the response is `200`
  with empty `content` and `page.totalElements` `0`.
- **AC-3:** Given a user with 3 deposits `D1 < D2 < D3` (by transaction ID), when the client requests `page=0&size=2`,
  then `content` has `D3`, `D2` in that order, `page.totalElements` is `3` and `page.totalPages` is `2`; when the client
  requests `page=1&size=2`, then `content` has only `D1`.
- **AC-4:** Given users A and B who each made a deposit, when the client requests A's statement, then it contains A's
  deposit and does not contain B's deposit transaction ID.
- **AC-5:** Given a user, when the client requests the statement without parameters, then `page.size` is `20`; with
  `size=500`, then `page.size` is `100`; with `sort=amount,asc`, then the response is `200` and the items are still in
  descending transaction ID order.
- **AC-6:** Given no user with a given ID, when the client requests its statement, then the response is `404` with
  `Content-Type: application/problem+json`.

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
