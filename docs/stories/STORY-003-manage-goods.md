---
id: STORY-003
title: Manage goods
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: [STORY-001]
related: []
---

# STORY-003: Manage goods

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

## Context

The shop sells **goods**. Each good has a name, the quantity in stock, and a price. The shop needs to add goods, look
them up, list them, and change their name, price or stock. Creating a good is a `POST`, so it uses the `Idempotency-Key`
mechanism from STORY-001. Updates set absolute values, so they are idempotent by nature.

## Requirements

- **R-1:** A client can create a good with a `name`, a `quantity` in stock and a `price`.
- **R-2:** `name` is trimmed and must be 1–200 characters long after trimming. `quantity` is an integer from `0` to
  `1000000`. `price` is a money string in the project's money format (STORY-001) from `"0.01"` to `"10000000.00"`; any
  other type or format is rejected with `400`. Names don't have to be unique.
- **R-3:** Creating a good requires an `Idempotency-Key` and follows the idempotency rules of STORY-001 (replay, key
  reuse with a different request → `422`, concurrent duplicates → at most one good).
- **R-4:** A client can read a good by ID. An unknown ID returns `404`.
- **R-5:** A client can list goods with pagination and sorting.
- **R-6:** A client can partially update a good's `name`, `quantity` and/or `price` with absolute values. Absent or
  `null` fields stay unchanged, and at least one field must be given. The same validation as on creation applies.
- **R-7:** Repeating the same update gives the same `200` response and leaves the good unchanged; `updatedAt` changes
  only when a value actually changes.
- **R-8:** Updating an unknown good returns `404`.

## Out of scope

- Deleting goods.
- Relative stock changes (increment / decrement) through the API. Stock is decreased by orders (STORY-005).
- Categories, descriptions, images, currencies.

## Technical details

### API

| Method  | Path                 | Request body                 | Success response            | Error responses               |
|---------|----------------------|------------------------------|-----------------------------|-------------------------------|
| `POST`  | `/api/v1/goods`      | `{name, quantity, price}`    | `201` + `Location` + good   | `400`, `409`, `422`           |
| `GET`   | `/api/v1/goods/{id}` | —                            | `200` + good                | `404`                         |
| `GET`   | `/api/v1/goods`      | —                            | `200` + page of goods       | `400` (unknown sort property) |
| `PATCH` | `/api/v1/goods/{id}` | `{name?, quantity?, price?}` | `200` + good                | `400`, `404`, `409`           |

**Create request** (`Content-Type: application/json`, header `Idempotency-Key` required):

```json
{ "name": "Coffee mug", "quantity": 10, "price": "15.00" }
```

**Good response** (`POST` 201, `GET` 200, `PATCH` 200). `Location` path ends with `/api/v1/goods/{id}`:

```json
{
  "id": 1, "name": "Coffee mug", "quantity": 10, "price": "15.00",
  "createdAt": "2026-11-08T12:00:00.123Z", "updatedAt": "2026-11-08T12:00:00.123Z"
}
```

On creation `updatedAt` equals `createdAt`.

**Validation** (all → `400`): `name` required/not blank/1–200 after trimming; `quantity` required, `0..1000000`;
`price` required, a money string (STORY-001 *Money format*) from `"0.01"` to `"10000000.00"`, so a JSON number or a
badly formatted string is rejected; malformed JSON. The database stores `price` in minor units (`"15.00"` → `1500`). For `PATCH`, the same rules apply to every non-null field, and a body
without any non-null field → `400`.

**Pagination:** project standard from STORY-001 (`page`, `size` default 20 / max 100, `sort`, `content` + `page`
object). Allowed sort properties: `id`, `name`, `quantity`, `price`, `createdAt`. Default sort `id,asc`.

**Errors** (`ProblemDetail`): idempotency errors as in STORY-001; `404` for an unknown good; `409`, title
`Concurrent modification`, when an update loses an optimistic-locking race (`@Version`).

### Data model

Migration `V{n}__create_goods.sql`, where `n` is the next free Flyway version at implementation time:

```sql
CREATE TABLE goods (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    quantity   INTEGER NOT NULL CHECK (quantity >= 0),
    price      INTEGER NOT NULL CHECK (price > 0),
    version    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
) STRICT;
```

`price` is in minor units (cents). The upper bounds (quantity `1000000`, price `"10000000.00"` = `1000000000`) are
API validation rules only. The database guarantees `quantity >= 0` and
`price > 0`.

### Affected code

- `good/`: `Good.kt`, `GoodRepository.kt`, `GoodService.kt`, `GoodController.kt`, `GoodDtos.kt`.
- `common/idempotency/`: reused as is for `POST /api/v1/goods`.
- `common/`: map `OptimisticLockingFailureException` to `409 Concurrent modification` (if not already mapped).

### Constraints and notes

- `@Version` on `Good` (`version` column): STORY-005 decreases stock concurrently with updates.
- Add `Concurrent modification` to the error titles table in `AGENTS.md`.

## Acceptance criteria

Tests share one database, so every test uses its own unique idempotency keys and good names.

- **AC-1:** Given a new key, when the client sends `POST /api/v1/goods` with
  `{"name":"Coffee mug","quantity":10,"price":"15.00"}`, then the response is `201`, the `Location` path ends with
  `/api/v1/goods/{id}`, the body has that `id`, `name` `"Coffee mug"`, `quantity` `10`, `price` `"15.00"` (a JSON
  string), and `createdAt` = `updatedAt` as ISO-8601 UTC timestamps ending in `Z`, and the `goods` row has
  `name = 'Coffee mug'`, `quantity = 10` and `price = 1500`.
- **AC-2:** Given a new key, when the client creates a good with `name` `"  Tea  "`, then the response and the row have
  `name` `"Tea"`. A good with `quantity` `0` and `price` `"0.01"` (row `price = 1`) and one with `quantity` `1000000`
  and `price` `"10000000.00"` (row `price = 1000000000`) are accepted (`201`), and their responses return the same
  `price` strings.
- **AC-3:** Given a new key for each case, when the client sends a create request with (a) no `name`, (b) `name` `"  "`,
  (c) a `name` of 201 characters, (d) no `quantity`, (e) `quantity` `-1`, (f) `quantity` `1000001`, (g) no `price`,
  (h) `price` `"0.00"`, (i) `price` `"10000000.01"`, (j) `price` as a JSON number `15`, (k) `price` as a JSON number
  `15.00`, (l) `price` `"15"`, (m) `price` `"15.5"`, (n) `price` `"15.000"`, (o) `price` `"15,00"`, (p) `price`
  `"-1.00"`, or (q) malformed JSON, then each response is `400` with `Content-Type: application/problem+json` and the
  number of rows in `goods` is unchanged.
- **AC-4:** Given a `POST /api/v1/goods` with key `K` returned `201`, when the client sends the same request with key `K`
  again, then the response is `201` with `Idempotent-Replayed: true`, the same `Location` and a JSON-equal body, and
  `goods` has exactly one row with that name.
- **AC-5:** Given a `POST /api/v1/users` with key `K` returned `201`, when the client sends a valid `POST /api/v1/goods`
  with the same key `K`, then the response is `422` with title `Idempotency key reused` and no good with that name exists.
  When the client sends a valid `POST /api/v1/goods` without an `Idempotency-Key`, then the response is `400` with title
  `Idempotency-Key header required`.
- **AC-6:** Given a new key `K`, when the client sends 10 identical `POST /api/v1/goods` requests with key `K`
  concurrently, then `goods` has exactly one row with that name, every response is `201` (JSON-equal bodies) or `409`
  with title `Request in progress`, and at least one is `201`.
- **AC-7:** Given an existing good, when the client sends `GET /api/v1/goods/{id}`, then the response is `200` with the
  same body as on creation. For an unknown ID the response is `404` with `Content-Type: application/problem+json`.
- **AC-8:** Given 3 goods with prices `"3.00"`, `"1.00"` and `"2.00"` exist (plus any others), when the client sends
  `GET /api/v1/goods?sort=price,desc&size=100`, then the response is `200`, `content` is ordered by numeric `price`
  value descending (for example `"10.00"` before `"9.00"`),
  and `page.totalElements` equals `SELECT count(*) FROM goods`. When the client sends `size=1&page=1`, then `content`
  has 1 element and `page.number` is `1`. When the client sends `sort=unknownField,asc`, then the response is `400`.
- **AC-9:** Given a good with `quantity` `10`, when the client sends `PATCH /api/v1/goods/{id}` without an
  `Idempotency-Key` and with `{"quantity":25}`, then the response is `200` with `quantity` `25`, unchanged `name` and
  `price`, unchanged `createdAt`, and `updatedAt` later than or equal to `createdAt`; the `goods` row has
  `quantity = 25`. Patching `{"name":" Big mug ","price":"25.00"}` returns `name` `"Big mug"` and `price` `"25.00"`,
  and the row has `name = 'Big mug'` and `price = 2500`.
- **AC-10:** Given a `PATCH` with body `B` returned `200`, when the client sends the same `PATCH` with body `B` again,
  then the response is `200` with a JSON body equal to the first response (including `updatedAt`) and the `goods` row
  is unchanged.
- **AC-11:** Given a good, when the client sends `PATCH` with (a) `{}`, (b) `{"quantity":null}`, (c) `{"quantity":-1}`,
  (d) `{"price":"0.00"}`, (e) `{"price":2500}` (a JSON number), (f) `{"price":"25"}`, (g) `{"name":""}`, or
  (h) malformed JSON, then each response is `400` and the `goods` row is unchanged.
- **AC-12:** Given no good with a given ID, when the client sends `PATCH /api/v1/goods/{id}` with `{"quantity":1}`, then
  the response is `404` with `Content-Type: application/problem+json`.

- **AC-13:** Given a new key for each good, when the client creates goods with `price` `"0.29"`, `"0.57"`, `"1.15"` and
  `"9999999.99"`, then each response returns exactly the same `price` string, the rows have `price` `29`, `57`, `115`
  and `999999999` respectively (values that floating-point conversion gets wrong), and `typeof(price)` is `'integer'`
  for each row. Patching a good to `"0.29"` and then reading it with `GET` returns `"0.29"` and row `price = 29`.

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
