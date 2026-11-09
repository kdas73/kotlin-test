---
id: STORY-003
title: Manage goods
status: Completed
created: 2026-11-08
updated: 2026-11-09
depends_on: [STORY-001, STORY-009]
related: []
---

# STORY-003: Manage goods

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |
| 2026-11-09 | Pending | Test Writing | test-writer | Writing acceptance tests |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 1 written; 38 acceptance tests red for the expected reasons |
| 2026-11-09 | Test Review | Ready | test-reviewer | Tests locked |
| 2026-11-09 | Ready | In Progress | implementer | Implementation started |
| 2026-11-09 | In Progress | In Review | implementer | Attempt 1: build green, 38/38 locked tests pass, lock verified |
| 2026-11-09 | In Review | Completed | reviewer | Review 1 approved; minor findings F-1, F-2 for follow-up |

## Context

The shop sells **goods**. Each good has a name, the quantity in stock, and a price. The shop needs to add goods, look
them up, list them, and change their name, price or stock. Creating a good is a `POST`, so it uses the `Idempotency-Key`
mechanism from STORY-001. Updates set absolute values, so they are idempotent by nature.

## Requirements

- **R-1:** A client can create a good with a `name`, a `quantity` in stock and a `price`.
- **R-2:** `name` is trimmed and must be 1–200 characters (Unicode code points, STORY-009) long after trimming. `quantity` is an integer from `0` to
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

**Validation** (all → `400`): `name` required/not blank/1–200 Unicode code points after trimming; `quantity` required, `0..1000000`;
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
- `common/validation/`: reuse the code-point length constraint from STORY-009 for `name` (not `@Size`).

### Constraints and notes

- `@Version` on `Good` (`version` column): STORY-005 decreases stock concurrently with updates.
- Add `Concurrent modification` to the error titles table in `AGENTS.md`.
- 2026-11-09 (story-writer, from STORY-001 Review 1 F-2): the `name` length is counted in Unicode code points, as
  decided in STORY-009; this story now depends on STORY-009 and has AC-14.

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
- **AC-14:** Given a new key, when the client creates a good with `name` = 200 × `😀` (U+1F600, one code point and two
  UTF-16 units each), then the response is `201` with that `name` and `SELECT length(name)` of the row returns `200`.
  With another new key and `name` = 201 × `😀`, the response is `400` and the number of rows in `goods` is unchanged.
  Patching the first good with `{"name":"<201 × 😀>"}` returns `400` and the row is unchanged.

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
- `src/test/kotlin/com/example/kotlintest/acceptance/Story003ManageGoodsAcceptanceTest.kt`

**AC coverage:**

| AC    | Test (class › method) |
|-------|-----------------------|
| AC-1  | `Story003ManageGoodsAcceptanceTest` › `AC-1 creates a good and returns 201 with Location, body and stored row` |
| AC-2  | `Story003ManageGoodsAcceptanceTest` › `AC-2 trims the name`; `AC-2 accepts quantity 0 with price 0_01 and quantity 1000000 with price 10000000_00` |
| AC-3  | `Story003ManageGoodsAcceptanceTest` › `AC-3 (a)` … `AC-3 (q)` (17 tests, one per case) |
| AC-4  | `Story003ManageGoodsAcceptanceTest` › `AC-4 replays the stored response for the same key and the same request` |
| AC-5  | `Story003ManageGoodsAcceptanceTest` › `AC-5 rejects a key used by another endpoint with 422 and a request without key with 400` |
| AC-6  | `Story003ManageGoodsAcceptanceTest` › `AC-6 concurrent identical requests with the same key create at most one good` |
| AC-7  | `Story003ManageGoodsAcceptanceTest` › `AC-7 reads a good by id and returns 404 problem detail for an unknown id` |
| AC-8  | `Story003ManageGoodsAcceptanceTest` › `AC-8 lists goods sorted by numeric price descending with pagination` |
| AC-9  | `Story003ManageGoodsAcceptanceTest` › `AC-9 updates quantity without an Idempotency-Key, then name and price` |
| AC-10 | `Story003ManageGoodsAcceptanceTest` › `AC-10 repeating the same PATCH returns the same 200 body and leaves the row unchanged` |
| AC-11 | `Story003ManageGoodsAcceptanceTest` › `AC-11 (a)` … `AC-11 (h)` (8 tests, one per case) |
| AC-12 | `Story003ManageGoodsAcceptanceTest` › `AC-12 returns 404 problem detail when patching an unknown good` |
| AC-13 | `Story003ManageGoodsAcceptanceTest` › `AC-13 stores and returns prices exactly as integer minor units` |
| AC-14 | `Story003ManageGoodsAcceptanceTest` › `AC-14 accepts a name of 200 emoji and rejects 201 emoji on create and on patch` |

**Red run:** `python scripts/app.py test --rerun` (`gradlew test`): compiles with no warnings; 120 tests, 38 failed. All
38 failures are the new STORY-003 tests; all 82 other tests (Story001, Story002, Story009, guard and unit tests) pass.

| Test | Failure reason (expected before implementation) |
|------|--------------------------------------------------|
| `AC-1`, `AC-2` (both), `AC-4`, `AC-7`, `AC-13`, `AC-14` | `POST /api/v1/goods` returns `404 Not Found` ("No static resource api/v1/goods."): endpoint does not exist yet; expected `201` |
| `AC-5` | `POST /api/v1/goods` with a reused key returns `404` instead of `422` (no handler, so the idempotency interceptor does not run) |
| `AC-6` | all 10 concurrent requests return `404`; expected `201` or `409 Request in progress` |
| `AC-3 (a)` … `AC-3 (q)` | `SELECT count(*) FROM goods` before the request fails: `no such table: goods` (migration missing) |
| `AC-8`, `AC-9`, `AC-10`, `AC-11 (a)` … `AC-11 (h)` | SQL setup `INSERT INTO goods ...` fails: `no such table: goods` |
| `AC-12` | SQL precondition `SELECT count(*) FROM goods WHERE id = ?` fails: `no such table: goods` |

**Notes:**
- **Isolation:** the test DB is shared, so no table is cleaned (later stories will reference `goods`). Every test uses
  its own UUID-based good names and idempotency keys, finds rows by `id` or unique name, and asserts only on its own
  data or against SQL over the whole table. The AC-1 and AC-2 names (`"Coffee mug"`, `"  Tea  "`) are taken literally
  from the ACs and are checked by `id`.
- **SQL setup:** AC-8 to AC-11 insert goods directly with `INSERT INTO goods (name, quantity, price, created_at,
  updated_at)` using the fixed past timestamp `2026-11-08T12:00:00.123Z` (project timestamp format; `version` uses its
  default). The past `createdAt` lets AC-9 check `updatedAt >= createdAt` and AC-10 check that a repeated PATCH keeps the
  `updatedAt` of the first one. AC-7, AC-13 and AC-14 create goods through the API, as the ACs describe.
- **AC-3 / AC-11 / AC-12 / AC-7 / AC-8 errors:** every error response is checked as a `ProblemDetail` (status,
  `Content-Type: application/problem+json`, `status` field), with the exact title only where an AC names one (AC-5,
  AC-6).
- **"Row unchanged"** (AC-10, AC-11, AC-14) compares the whole row (`SELECT *`), including `version` and `updated_at`,
  as the ACs say. So a repeated identical PATCH (AC-10) must not write the row.
- **AC-8:** besides the required goods priced `3.00`, `1.00`, `2.00`, the test inserts goods priced `10.00` and `9.00`
  ("plus any others") to catch lexical instead of numeric ordering. It requests exactly
  `?sort=price,desc&size=100` and, if there is more than one page, follows with `&page=N`, so it doesn't depend on how
  many goods other tests created. It asserts that the listed prices are in descending numeric order, equal
  `SELECT price FROM goods ORDER BY price DESC` (formatted), list the own goods as 10.00, 9.00, 3.00, 2.00, 1.00, and
  that `page.totalElements` equals `SELECT count(*) FROM goods`. No tie-breaker order between equal prices is asserted.
- **AC-5:** the key is first used by `POST /api/v1/users` (a valid user with a unique email); the second part sends a
  valid good without any `Idempotency-Key` header. Both parts also check that no good with the request's name exists.
- **AC-6:** 10 virtual threads start together on a latch, as in STORY-001 AC-14.
- **AC-13:** the `"9999999.99"` good is then patched to `"0.29"` and read back with `GET`; rows are checked with
  `typeof(price) = 'integer'`.
- **AC-14:** `SELECT length(name)` counts characters (code points) for TEXT in SQLite; bodies are sent as UTF-8 bytes.
- `PATCH` requests never send an `Idempotency-Key` header. Unknown IDs use `Long.MAX_VALUE` with an SQL precondition
  that no such row exists.

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
| AC-1  | yes     | yes | `201`; `Location` path ends with `/api/v1/goods/{id}`; `id` integral; `name`, integral `quantity`, `price` checked as a JSON string; `createdAt` = `updatedAt`, ISO-8601 UTC ending in `Z`; row `name`, `quantity`, `price = 1500` by `id`. |
| AC-2  | yes     | yes | Trim checked in the response and the row; both boundaries return `201` with the same `price` strings and rows `price = 1` / `1000000000`, `quantity` `0` / `1000000`. |
| AC-3  | yes     | yes | 17 tests, (a)–(q), one per case. In each, the other fields are valid. Each checks `400`, `application/problem+json` and an unchanged `count(*)` of `goods`. JSON numbers (j, k) are sent as raw numbers; (q) is truncated JSON. |
| AC-4  | yes     | yes | `201`, `Idempotent-Replayed: true`, same `Location`, JSON-equal body, exactly one row with the name. |
| AC-5  | yes     | yes | Key first used by a valid `POST /api/v1/users` (the setup asserts `201`). The goods request returns `422` with title `Idempotency key reused`, and no row with that name exists. Without a key: `400` with title `Idempotency-Key header required`, and no row exists. |
| AC-6  | yes     | yes | 10 virtual threads released together by a latch, joined with a timeout (no sleeps). Every non-`201` response must be `409` with title `Request in progress`; at least one `201`; the `201` bodies are JSON-equal; exactly one row. |
| AC-7  | yes     | yes | `GET` body JSON-equal to the creation body. Unknown ID (`Long.MAX_VALUE`, with an SQL precondition that it is absent) returns `404` problem+json. |
| AC-8  | yes     | yes | Own goods priced 3.00, 1.00, 2.00, plus 10.00 and 9.00 to catch lexical ordering. The test follows every page of `sort=price,desc&size=100` and checks numeric descending order, equality with `SELECT price ... ORDER BY price DESC`, and the order of its own goods. `totalElements` = `count(*)`. `size=1&page=1` returns 1 element with `page.number` `1`. An unknown sort property returns `400`. |
| AC-9  | yes     | yes | No `Idempotency-Key` on `PATCH`. Checks `quantity` 25, unchanged `name`, `price` and `createdAt` (equal to the inserted value), `updatedAt` ≥ `createdAt` and the row. The second patch checks trimming, `"25.00"`, and the row's `name` and `price = 2500`. |
| AC-10 | yes     | yes | Second identical `PATCH` returns `200` with a JSON-equal body (including `updatedAt`); the whole row (`SELECT *`) is equal to the row after the first `PATCH`. |
| AC-11 | yes     | yes | 8 tests, (a)–(h). Each checks `400` problem+json and a whole row unchanged on its own freshly inserted good. |
| AC-12 | yes     | yes | SQL precondition that the ID is absent; `404` problem+json. |
| AC-13 | yes     | yes | Exact response strings, row values `29`, `57`, `115`, `999999999` and `typeof(price) = 'integer'`. The `9999999.99` good is patched to `0.29`, then the `PATCH` response, `GET` and row are checked. |
| AC-14 | yes     | yes | 200 × U+1F600 is accepted with the same `name`, and `length(name) = 200`. 201 × U+1F600 is rejected with `400` and an unchanged row count. A `PATCH` to 201 × U+1F600 returns `400`, and the whole row is unchanged. Bodies are sent as UTF-8 bytes. |

**Quality checks:** black-box only (no production imports): yes. The only imports are JUnit, Spring test/HTTP/JDBC,
Jackson, `java.*` and `kotlin.test`; access is HTTP through `RestTestClient` and SQL through `JdbcTemplate`.
Deterministic: yes. There are no sleeps. AC-6 uses a latch and bounded joins. Timestamps are compared to a fixed past
value or to each other. Isolated test data: yes. Each test uses UUID-based names and keys and finds rows by `id` or
unique name. Whole-table assertions (row counts, AC-8 listing) rely on sequential execution, and no
`junit-platform.properties` enables parallelism. No over-specification: yes. Readable with AC-prefixed names: yes.
English: yes. No `@Disabled` or tags: yes. No changes to `build.gradle.kts` or `src/test/resources`: yes (`git status`
shows only the story and the new test file). Red for the expected reason (own run): yes.

**Red run (own):** `python scripts/app.py test --rerun` → 120 tests, 38 failures, 0 errors, 0 skipped. All 38 failures
are in `Story003ManageGoodsAcceptanceTest`; all other classes (Story001, Story002, Story009, guard and unit tests) pass.
Failure reasons observed:
- AC-1, AC-2 (both), AC-7, AC-13 and AC-14: `POST /api/v1/goods` returns `404` ("No static resource api/v1/goods.").
- AC-5: the user setup returns `201`; then the goods request returns `404` instead of `422`.
- AC-6: `404` instead of `201`/`409`.
- AC-3 (a)–(q), AC-8 to AC-12: `no such table: goods`.

None of the failures comes from a defect in the tests.

**Notes for the implementer (not findings):**
- AC-10 and AC-11 compare the whole row, including `version` and `updated_at`. A repeated identical `PATCH` or a rejected
  `PATCH` must not write the row. Spring Data JDBC increments `@Version` on every `save`, so skip the save when no value
  changes. This follows from AC-10 ("the goods row is unchanged") and R-7.
- AC-8 requires pages of 100 to work over the whole table and `totalElements` to match `count(*)`.

**Findings:** none.

**Locked tests:**

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/Story003ManageGoodsAcceptanceTest.kt` | e124fac049bd99ab06ef6185561861db8d4eaa71dca2c3c2636e123ce2bef803 |

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

**Summary:** Added the `good/` feature: create (idempotent `POST`), read by ID, paginated and sorted list, and partial
`PATCH` with absolute values on `/api/v1/goods`, backed by the new `goods` table. `price` is a money string in JSON
and `Long` cents in code and in the database. JSON numbers for `price` (and non-integers for `quantity`) are rejected
while the JSON is read, through new strict deserializers, so they are never coerced. `PATCH` doesn't write when no
value changes, so `updatedAt` and `version` stay the same (R-7, AC-10). `@Version` optimistic-locking failures are
mapped to `409 Concurrent modification`.

**Changed files:**
- `src/main/resources/db/migration/V2__create_goods.sql`: new `goods` table, exactly as in *Technical details*.
- `src/main/kotlin/com/example/kotlintest/good/Good.kt`: entity (`@Version`), `GoodNotFoundException` (`404 Good not found`).
- `src/main/kotlin/com/example/kotlintest/good/GoodRepository.kt`: Spring Data JDBC repository with paging.
- `src/main/kotlin/com/example/kotlintest/good/GoodDtos.kt`:
  - `CreateGoodRequest` / `UpdateGoodRequest` trim `name` in their `@JsonCreator`.
  - Validation: `@CodePointLength(1, 200)`, `@Min(0)`/`@Max(1000000)`, `@MoneyAmount("0.01", "10000000.00")`; `PATCH`
    needs at least one non-null field.
  - `GoodResponse` formats `price` with `Money.format`.
- `src/main/kotlin/com/example/kotlintest/good/GoodService.kt`:
  - `create`, `get`, `list` (sort properties `id`, `name`, `quantity`, `price`, `createdAt`).
  - `update` skips the save when nothing changes.
  - Timestamps come from the injected `Clock`, truncated to milliseconds.
- `src/main/kotlin/com/example/kotlintest/good/GoodController.kt`: the four endpoints. `POST` runs through
  `IdempotentExecutor`; `PATCH` takes no idempotency key.
- `src/main/kotlin/com/example/kotlintest/common/json/StrictDeserializers.kt` (new):
  - `StringOnlyDeserializer` accepts only JSON strings.
  - `IntegerOnlyDeserializer` accepts only JSON integers that fit into an `Int`.
- `src/main/kotlin/com/example/kotlintest/common/money/MoneyAmount.kt` (new): a Jakarta constraint. The value must be
  in the request money format and within `[min, max]`; the check uses `Money.parse`.
- `src/main/kotlin/com/example/kotlintest/common/ApiExceptionHandler.kt`: `OptimisticLockingFailureException` →
  `409`, title `Concurrent modification`, generic detail.
- `AGENTS.md`:
  - error titles `Good not found` and `Concurrent modification`;
  - how to accept money and strict integer request fields;
  - `json/` in the project layout.
- Own tests (not locked):
  - `src/test/kotlin/com/example/kotlintest/good/GoodServiceTest.kt`
  - `src/test/kotlin/com/example/kotlintest/good/GoodApiTest.kt`
  - `src/test/kotlin/com/example/kotlintest/common/money/MoneyAmountTest.kt`
  - `src/test/kotlin/com/example/kotlintest/common/json/StrictDeserializersTest.kt`

**Migrations:** `V2__create_goods.sql`.

**Acceptance criteria evidence:**

| AC    | Locked acceptance test (now passing) | Additional tests by the implementer |
|-------|--------------------------------------|-------------------------------------|
| AC-1  | `Story003ManageGoodsAcceptanceTest` › `AC-1 creates a good and returns 201 with Location, body and stored row` | `GoodServiceTest` › `create sets createdAt and updatedAt to the current time truncated to milliseconds` |
| AC-2  | `Story003ManageGoodsAcceptanceTest` › `AC-2 trims the name`; `AC-2 accepts quantity 0 with price 0_01 and quantity 1000000 with price 10000000_00` | `MoneyAmountTest` › `amounts within the range are valid, including both bounds` |
| AC-3  | `Story003ManageGoodsAcceptanceTest` › `AC-3 (a)` … `AC-3 (q)` (17 tests) | `MoneyAmountTest` › `amounts outside the range are invalid`, `badly formatted amounts are invalid`; `StrictDeserializersTest` › `rejects numbers, booleans and objects for a string`; `GoodApiTest` › `a non-integer quantity is rejected on create and on patch` |
| AC-4  | `Story003ManageGoodsAcceptanceTest` › `AC-4 replays the stored response for the same key and the same request` | — |
| AC-5  | `Story003ManageGoodsAcceptanceTest` › `AC-5 rejects a key used by another endpoint with 422 and a request without key with 400` | — |
| AC-6  | `Story003ManageGoodsAcceptanceTest` › `AC-6 concurrent identical requests with the same key create at most one good` | — |
| AC-7  | `Story003ManageGoodsAcceptanceTest` › `AC-7 reads a good by id and returns 404 problem detail for an unknown id` | `GoodApiTest` › `an unknown good returns 404 titled Good not found on GET and PATCH`, `a non-numeric good id returns a 400 problem detail` |
| AC-8  | `Story003ManageGoodsAcceptanceTest` › `AC-8 lists goods sorted by numeric price descending with pagination` | `GoodApiTest` › `every allowed sort property is accepted`, `unknown sort property returns 400 titled Invalid sort property` |
| AC-9  | `Story003ManageGoodsAcceptanceTest` › `AC-9 updates quantity without an Idempotency-Key, then name and price` | `GoodServiceTest` › `a changed value is saved with a new updatedAt and the loaded version`, `null arguments keep the current values` |
| AC-10 | `Story003ManageGoodsAcceptanceTest` › `AC-10 repeating the same PATCH returns the same 200 body and leaves the row unchanged` | `GoodServiceTest` › `setting the current values does not write` |
| AC-11 | `Story003ManageGoodsAcceptanceTest` › `AC-11 (a)` … `AC-11 (h)` (8 tests) | `StrictDeserializersTest` › `rejects floats, strings and booleans for an integer`, `null and absent values stay null` |
| AC-12 | `Story003ManageGoodsAcceptanceTest` › `AC-12 returns 404 problem detail when patching an unknown good` | `GoodServiceTest` › `an unknown good throws GoodNotFoundException` |
| AC-13 | `Story003ManageGoodsAcceptanceTest` › `AC-13 stores and returns prices exactly as integer minor units` | — |
| AC-14 | `Story003ManageGoodsAcceptanceTest` › `AC-14 accepts a name of 200 emoji and rejects 201 emoji on create and on patch` | — |

Also: `GoodApiTest` › `an optimistic locking failure is a 409 titled Concurrent modification` (the `@Version`
mapping required by *Technical details*).

**Build:** `python scripts/app.py build` (`gradlew.bat build`): BUILD SUCCESSFUL, 140 tests, 0 failures.

```
> Task :test
> Task :check
> Task :build
BUILD SUCCESSFUL in 13s
=== Test summary ===
  com.example.kotlintest.acceptance.Story001RegisterUsersAcceptanceTest: 26 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story002UpdateUserAcceptanceTest: 14 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story003ManageGoodsAcceptanceTest: 38 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story009CodePointLengthAcceptanceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.idempotency.IdempotencyKeyTest: 4 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.json.StrictDeserializersTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.money.MoneyAmountTest: 4 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.money.MoneyTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.validation.CodePointLengthTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.config.SqliteDialectTest: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.config.VirtualThreadsTest: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.good.GoodApiTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.good.GoodServiceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.KotlinTestApplicationTests: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserApiTest: 10 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserPersistenceTest: 3 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserServiceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  TOTAL: 140 tests, 0 failures, 0 errors, 0 skipped
build: SUCCESSFUL
```

Before the implementation, `python scripts/app.py test --story 003` failed for the expected reasons: `no such table:
goods` and `404 No static resource api/v1/goods`.

**Locked tests verify:**

```
$ python scripts/locked-tests.py verify STORY-003
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story003ManageGoodsAcceptanceTest.kt
STORY-003-manage-goods: 1/1 locked tests unchanged
```

The earlier stories' locks also verify `OK`: STORY-001, STORY-002, STORY-009.

**Deviations and notes:**
- **Strict `quantity`:** R-2 says `quantity` is an integer. Jackson's default coercion would turn `10.5` into `10` and
  `"10"` into `10`, so `quantity` uses `IntegerOnlyDeserializer` and those values return `400`. The locked tests don't
  cover this; `GoodApiTest` does.
- **Compiler warnings:** a forced recompile (`gradlew compileKotlin --rerun compileTestKotlin --rerun`) shows no
  warnings in production code or in my tests. Two warnings come from locked acceptance test files, which I must not
  edit:
  - `Story003ManageGoodsAcceptanceTest.kt:620`: "Unnecessary safe call on a non-null receiver of type 'ByteArray'"
    (new with this story's tests);
  - `Story009CodePointLengthAcceptanceTest.kt:146`: the same warning (it was already there).
- **SQLite and concurrent `PATCH`:** like `UserService.update`, `PATCH` reads and then writes in one transaction. In
  WAL mode, a concurrent writer that commits between the read and the write can make SQLite report
  `SQLITE_BUSY_SNAPSHOT`, which becomes a `500`, instead of a `@Version` conflict. No AC covers this. It matters for
  STORY-005 (concurrent stock changes); I report it and didn't change it.
- **Smoke test (sandbox, port 8081):**
  - `POST` with `" Coffee mug "`/`10`/`"15.00"` → `201`, `Location: /api/v1/goods/1`, trimmed name.
  - `PATCH {"price":"0.29"}` → `200` with `"0.29"` and a new `updatedAt`.
  - `POST` with `"price":15` → `400` problem.
  - `GET ?sort=price,desc` → the standard page shape.
  - The sandbox was stopped afterwards.
- Test defects: none.

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
| R-1 | yes | `GoodController.create` → `GoodService.create` inserts a `goods` row; `GoodResponse` returns `price` via `Money.format`. |
| R-2 | yes | `CreateGoodRequest.of` trims `name`; `@NotBlank` + `@CodePointLength(1, 200)` (STORY-009 constraint, not `@Size`); `quantity` `@NotNull @Min(0) @Max(1000000)` with `IntegerOnlyDeserializer`; `price` `@NotNull @MoneyAmount("0.01", "10000000.00")` with `StringOnlyDeserializer`, so JSON numbers are rejected while reading. No uniqueness constraint on `name`. |
| R-3 | yes | `POST` runs inside `IdempotentExecutor.execute`, which builds the full `ResponseEntity`; the shared `IdempotencyInterceptor` handles replay, `422` and `409`. |
| R-4 | yes | `GoodService.get` throws `GoodNotFoundException` (`404`, title `Good not found`). |
| R-5 | yes | `list` uses `pageable.withValidatedSort(id, name, quantity, price, createdAt)` and `PageResponse.from`; `size=500` is capped to `100` (observed in sandbox). |
| R-6 | yes | `UpdateGoodRequest`: null/absent fields keep their values, `@AssertTrue isChangeRequested` rejects an empty change, and the same constraints and deserializers as on create apply to non-null values. |
| R-7 | yes | `GoodService.update` compares the copied entity with the loaded one and returns without `save` when nothing changes, so `updatedAt` and `version` stay unchanged. |
| R-8 | yes | `update` calls `get` first → `404`. |

**Acceptance criteria:**

| AC    | Result | How checked |
|-------|--------|-------------|
| AC-1  | pass | Locked test passes (own clean build); code path read; sandbox `POST` → `201`, `Location: /api/v1/goods/1`. |
| AC-2  | pass | Locked tests pass; trimming in `CreateGoodRequest.of`; bounds in `@Min/@Max/@MoneyAmount`. |
| AC-3  | pass | 17 locked tests pass; rejection comes from the generic constraints and strict deserializers, not from special-casing; sandbox: oversized price string → `400` problem. |
| AC-4  | pass | Locked test passes; shared idempotency machinery, no goods-specific replay code. |
| AC-5  | pass | Locked test passes; fingerprint/key handling is the shared interceptor. |
| AC-6  | pass | Locked test passes. |
| AC-7  | pass | Locked test passes; `GoodApiTest` also checks the `Good not found` title. |
| AC-8  | pass | Locked test passes; `price` is an `INTEGER` column, so sorting is numeric; `id` tie-breaker added by `withValidatedSort`. |
| AC-9  | pass | Locked test passes; sandbox `PATCH` without `Idempotency-Key` → `200`, `{"name":null,"price":"0.29"}` keeps the name. |
| AC-10 | pass | Locked test passes; no-op detection in `GoodService.update`; covered by `GoodServiceTest` › `setting the current values does not write`. |
| AC-11 | pass | 8 locked tests pass; sandbox: `{"name":"   "}`, `{"quantity":10.5}`, `[]` and `{"foo":1}` → `400` problem+json. |
| AC-12 | pass | Locked test passes. |
| AC-13 | pass | Locked test passes; conversion only through `Money.parse` / `Money.format` (string + `Long` arithmetic). |
| AC-14 | pass | Locked test passes; `@CodePointLength` on `name` for create and patch. |

**Build:** `gradlew clean` then `python scripts/app.py build` (`gradlew.bat build`): BUILD SUCCESSFUL, 140 tests, 0
failures, 0 errors, 0 skipped. `build/test-results/test/TEST-...Story003ManageGoodsAcceptanceTest.xml`: `tests="38"
skipped="0" failures="0" errors="0"`. A forced recompile shows warnings only in the locked files
`Story003ManageGoodsAcceptanceTest.kt:620` and `Story009CodePointLengthAcceptanceTest.kt:146` (unnecessary safe call), as
reported in Attempt 1. Production code and the implementer's tests compile without warnings.

**Locked tests verify:**

```
$ python scripts/locked-tests.py verify STORY-003
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story003ManageGoodsAcceptanceTest.kt
STORY-003-manage-goods: 1/1 locked tests unchanged
```

STORY-001, STORY-002 and STORY-009 also verify `1/1 OK`. `src/test` has no `@Disabled`, assumptions or tags.
`build.gradle.kts`, `settings.gradle.kts` and `src/test/resources` are unchanged (`git status` / `git diff`).

**Other checks:**
- Conventions: `V2__create_goods.sql` matches *Technical details* exactly (`STRICT`, `INTEGER` money, `version`); `V1` is
  untouched. DTOs are separate from the `Good` entity. Constructor injection is used. There is no `!!` (`checkNotNull` is
  used after validation). Code is blocking only. Timestamps come from the injected `Clock`, truncated to milliseconds.
  Errors are `ProblemDetail`, and texts are in English.
- `409 Concurrent modification`: `ApiExceptionHandler` maps `OptimisticLockingFailureException`. Spring Data JDBC 4.1.2
  (`AggregateChangeExecutor` / `JdbcAggregateChangeExecutionContext`, read from the sources jar) throws it unwrapped
  from a stale-version update, so the mapping is reachable.
- `AGENTS.md` adds the `Good not found` and `Concurrent modification` titles, as *Constraints and notes* requires, plus
  the money/strict-integer DTO rules and `json/`.
- Scope: no delete, no relative stock changes, no extra fields. `StrictDeserializers` and `MoneyAmount` are needed to
  reject JSON-number prices (R-2) and are reusable.
- Sandbox (port 8081, stopped afterwards): 60 concurrent `PATCH` requests on one good (3 rounds of 20) all returned
  `200`, the final row was consistent, and the log had no `SQLITE_BUSY` or 5xx.
- Evidence accuracy: changed files, migration, test counts, lock output and warnings in Attempt 1 match what I observed.
- Observation (not a finding): a JSON number for `name` (`{"name":5}`) is coerced to `"5"`. This matches the existing
  user endpoints, and R-2 only requires a type check for `price`.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| F-1 | minor | `src/test/kotlin/com/example/kotlintest/good/GoodApiTest.kt:80` | The `409 Concurrent modification` mapping is tested only by calling `ApiExceptionHandler.handleOptimisticLocking` directly. No test drives a stale `@Version` save through `GoodService`/Spring Data JDBC and the HTTP stack. Expected: an integration test (for example saving a `Good` copy with an outdated `version` through the repository, or a service-level race) that asserts the `409` problem. That would catch a future change where the exception gets wrapped. |
| F-2 | minor | `src/main/kotlin/com/example/kotlintest/good/GoodService.kt:44-54` | Reported by the implementer under *Deviations and notes*. `update` reads and then writes in a deferred SQLite transaction. In WAL mode, a concurrent commit between the read and the write can raise `SQLITE_BUSY_SNAPSHOT`, which becomes a `500` instead of the `409` from *Technical details*. I could not reproduce it (60 concurrent `PATCH` requests → all `200`). No AC covers it, and `UserService.update` (STORY-002, Completed) uses the same pattern. Expected: address it in a follow-up story or in STORY-005, before stock is changed concurrently (for example retry or map the busy-snapshot error to `409`, or use `IMMEDIATE` write transactions). |
