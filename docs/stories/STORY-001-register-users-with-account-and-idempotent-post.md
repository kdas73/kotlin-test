---
id: STORY-001
title: Register users with an account and idempotent POST
status: Completed
created: 2026-11-08
updated: 2026-11-09
depends_on: []
related: []
---

# STORY-001: Register users with an account and idempotent POST

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |
| 2026-11-09 | Pending | Test Writing | test-writer | Writing acceptance tests |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 1: 26 acceptance tests, red run recorded |
| 2026-11-09 | Test Review | Ready | test-reviewer | Tests locked |
| 2026-11-09 | Ready | In Progress | implementer | Implementation started |
| 2026-11-09 | In Progress | In Review | implementer | Attempt 1: build green, 26/26 locked tests pass, lock verified |
| 2026-11-09 | In Review | Completed | reviewer | Review 1: Approved (minor findings F-1, F-2 for a follow-up story) |

## Context

We are building a simple shop. Its customers are **users**: each one has a name and an email, and **each user has
exactly one account** that holds their money. The account is created automatically when the user is registered.

All interfaces must be **idempotent**: a client may send the same request more than once, for example after a timeout,
and the request must take effect only once. Above all, this must stop money from being taken twice. `GET`, `PUT`,
`PATCH` (setting absolute values) and `DELETE` are idempotent by nature. `POST` is not, so this story introduces a generic
**`Idempotency-Key`** mechanism that every `POST` endpoint of the shop uses, starting with user registration.

This story is the base for the goods, ledger, order and statement stories.

## Requirements

- **R-1:** A client can register a user with a `name` and an `email`.
- **R-2:** `name` is trimmed and must be 1–100 characters long after trimming. `email` is trimmed, must be a valid email
  address of at most 254 characters, and is stored in lower case.
- **R-3:** Emails are unique, case-insensitively. Registering a second user with the same email in any letter case is
  rejected with `409 Conflict`.
- **R-4:** Registering a user also creates exactly one account for that user, with a zero balance, in the same database
  transaction.
- **R-5:** A client can read a user by ID. An unknown ID returns `404`.
- **R-6:** A client can list users with pagination and sorting.
- **R-7:** A client can read a user's account (ID, user ID, balance). An unknown user ID returns `404`.
- **R-8:** Every `POST` endpoint under `/api/v1` requires an `Idempotency-Key` request header. A request with a missing,
  blank or invalid key is rejected with `400` and has no side effects.
- **R-9:** When a `POST` is repeated with the same key and the same request (method, path and body), the system does not
  perform the operation again. It returns the stored original response (same status, body and `Location` header) with
  the extra header `Idempotent-Replayed: true`.
- **R-10:** When a key is reused with a different request (different method, path or body), the request is rejected with
  `422` and has no side effects.
- **R-11:** When several requests with the same key arrive concurrently, the operation is performed at most once. Every
  other request gets either the replayed response or `409 Conflict` ("request in progress").
- **R-12:** Only successful (`2xx`) responses are recorded under their key. A request that failed (for example a
  validation error or a conflict) leaves no record, so the client can fix the cause and retry with the same key.
- **R-13:** The idempotency record is committed in the same database transaction as the business change it belongs to:
  either both are stored or neither is.
- **R-14:** Every money amount in the API JSON is a string with a decimal amount in major units and exactly 2 fraction
  digits (see *Money format*). The database stores money as integer minor units.
- **R-15:** Money is always exact: it is never represented or computed as a floating-point number, in the database or in
  code (see *Money format* → *Exact types only*).

## Out of scope

- Authentication and authorization (any client may call any endpoint).
- Updating users (STORY-002) and deleting users.
- More than one account per user, system accounts and money movements (STORY-004).
- Multiple currencies.
- Expiry or cleanup of idempotency keys (keys are kept forever).
- Idempotency keys on methods other than `POST` (the header is ignored on them).

## Technical details

### API

| Method | Path                          | Request body       | Success response                       | Error responses               |
|--------|-------------------------------|--------------------|----------------------------------------|-------------------------------|
| `POST` | `/api/v1/users`               | `{name, email}`    | `201` + `Location` + user              | `400`, `409`, `422`           |
| `GET`  | `/api/v1/users/{id}`          | —                  | `200` + user                           | `404`                         |
| `GET`  | `/api/v1/users`               | —                  | `200` + page of users                  | `400` (unknown sort property) |
| `GET`  | `/api/v1/users/{id}/account`  | —                  | `200` + account                        | `404`                         |

**Create user request** (`Content-Type: application/json`, header `Idempotency-Key` required):

```json
{ "name": "Alice", "email": "alice@example.com" }
```

**User response** (`POST` 201 and `GET` 200). `Location` is `/api/v1/users/{id}` (absolute or relative URL whose path
ends with `/api/v1/users/{id}`):

```json
{ "id": 1, "name": "Alice", "email": "alice@example.com", "createdAt": "2026-11-08T12:00:00.123Z" }
```

**Account response:**

```json
{ "id": 1, "userId": 1, "balance": "0.00" }
```

`balance` is a money amount in the project's money format (see *Money format* below). There is a single currency.

**Money format** (project standard for every money field in the API: `balance`, `price`, `amount`, `unitPrice`,
`totalAmount`, ...):
- In JSON, money is always a **string** with a decimal amount in major currency units and exactly 2 fraction digits,
  for example `"50.00"`, `"0.05"`, `"-15.00"`.
- Responses: the string matches `^-?(0|[1-9][0-9]*)\.[0-9]{2}$` (no `+`, no thousands separators, no exponent, no extra
  leading zeros). A minus sign appears only on signed ledger amounts.
- Requests: a money field must be a JSON string matching `^(0|[1-9][0-9]*)\.[0-9]{2}$` (no sign). Anything else is
  rejected with `400`, for example a JSON number (`50` or `50.00`), `"50"`, `"50.5"`, `"50.000"`, `"50,00"`, `"-1.00"`,
  `" 50.00"` or `""`. Range limits are defined per field by the stories that introduce it.
- The database stores money as `INTEGER` minor units (cents): `"50.00"` ↔ `5000`. The conversion is exact
  (`minor = major × 100`) and never goes through floating point.
- **Exact types only.** In code, money is a `Long` number of minor units (or a value type wrapping one). Parsing and
  formatting use string/integer arithmetic or `BigDecimal` (with `scale = 2` and no rounding). `Double`, `Float`, SQL
  `REAL`/`NUMERIC` columns, and Jackson number nodes are never used for money anywhere: not in entities, DTOs, services,
  queries or migrations. Arithmetic on money (sums, `quantity × price`) is done on `Long` minor units, with overflow
  checks (`Math.multiplyExact` / `Math.addExact`).

**Validation** (all → `400`):
- `name`: required, not blank, 1–100 characters after trimming.
- `email`: required, at most 254 characters after trimming, matches `^[^@\s]+@[^@\s]+\.[^@\s]+$`.
- Malformed JSON → `400`.

**Pagination** (project standard for every collection endpoint):
- Query parameters `page` (0-based, default `0`), `size` (default `20`, values above `100` are capped at `100`),
  `sort` (`property,asc|desc`, default `id,asc`).
- Allowed sort properties for users: `id`, `name`, `email`, `createdAt`. Any other property → `400`.
- Response shape:

```json
{
  "content": [ { "id": 1, "name": "Alice", "email": "alice@example.com", "createdAt": "2026-11-08T12:00:00.123Z" } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

**Idempotency** (project standard for every `POST` endpoint):
- Header `Idempotency-Key`: 1–255 characters, each a visible ASCII character (`0x21`–`0x7E`). Clients should use UUIDs.
- The request fingerprint is: HTTP method, request path without the query string, and the SHA-256 hex digest of the raw
  request body bytes (an empty body hashes the empty input).
- Processing order:
  1. Header missing or blank → `400`, title `Idempotency-Key header required`.
  2. Header present but too long or with forbidden characters → `400`, title `Invalid Idempotency-Key header`.
  3. A record exists for the key with a different fingerprint → `422`, title `Idempotency key reused`.
  4. A record exists for the key with the same fingerprint → replay: the stored status, the stored body
     (`Content-Type: application/json`), the stored `Location` header (if any), and the header `Idempotent-Replayed: true`.
  5. Another request with the same key is still being processed → `409`, title `Request in progress`.
  6. Otherwise the request is processed normally (validation, business logic). If the response is `2xx`, the record is
     stored in the same database transaction as the business change.
- First (non-replayed) responses do not have an `Idempotent-Replayed` header.

**Errors:** all error responses are RFC 9457 `ProblemDetail`s (`Content-Type: application/problem+json`) with at least
`status` and `title`. No stack traces or SQL. Exact titles for domain errors:

| Status | Title                             | When                                   |
|--------|-----------------------------------|----------------------------------------|
| `400`  | `Idempotency-Key header required` | header missing or blank                |
| `400`  | `Invalid Idempotency-Key header`  | header too long or bad characters      |
| `409`  | `Email already in use`            | another user has this email (any case) |
| `409`  | `Request in progress`             | same key is being processed            |
| `422`  | `Idempotency key reused`          | same key, different request            |

### Data model

Migration `V1__create_users_accounts_idempotency.sql` (all tables `STRICT`; timestamps are ISO-8601 UTC `TEXT` such as
`2026-11-08T12:00:00.123Z`):

```sql
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
```

- User accounts have `kind = 'USER'` and a non-null `user_id`. The kinds `FUNDING` and `REVENUE` are system accounts
  without a user. They are seeded by STORY-004 and are defined here so the table does not have to be rebuilt later.
- A user account balance can never be negative (`CHECK`). `version` is for `@Version` optimistic locking.

### Affected code

- `user/`: `User.kt`, `UserRepository.kt`, `UserService.kt`, `UserController.kt`, `UserDtos.kt`.
- `account/`: `Account.kt`, `AccountRepository.kt`, `AccountController.kt` (or the endpoint in `UserController`),
  `AccountDtos.kt`.
- `common/idempotency/`: the generic `Idempotency-Key` mechanism (record entity + repository, and a component that runs
  a `POST` operation and stores its response in one transaction). It must be reusable by later stories' `POST` endpoints.
- `common/`: the single `@RestControllerAdvice` that maps domain exceptions to `ProblemDetail`.
- `config/`: Spring Data web paging configuration (page shape, max size `100`).

### Constraints and notes

- Plain blocking code on virtual threads. Keep transactions short. SQLite has a single writer: a deferred transaction that
  reads first and then writes can fail with `SQLITE_BUSY`. Concurrent duplicates must end as R-11 says (replay or
  `409 Request in progress`), never as `500`.
- This is the first temporal column in the project: add a `@DataJdbcTest` round-trip test for `Instant` ↔ ISO-8601 UTC
  `TEXT` (AGENTS.md → Persistence & SQLite rules).
- Update `AGENTS.md` with the new project conventions: the `Idempotency-Key` rules, the page response shape, the money
  format and the error titles table above.
- The money format is implemented once (for example a shared Jackson serializer/deserializer or a `Money` value type in
  `common/`) and reused by every story. Add unit tests for it with values that floating point gets wrong, for example
  `"0.29"` → `29`, `"0.57"` → `57`, `"1.15"` → `115`, `"10000000.00"` → `1000000000`, and back.
- R-15 is mostly not visible through the API. The reviewer checks it in the code: no `Double`, `Float`, `REAL` or
  `NUMERIC` on any money path. Later stories add black-box ACs with such values.

## Acceptance criteria

Tests share one database, so every test uses its own unique emails and idempotency keys.

- **AC-1:** Given no user with email `alice-<unique>@example.com`, when the client sends `POST /api/v1/users` with a new
  `Idempotency-Key` and `{"name":"Alice","email":"alice-<unique>@example.com"}`, then the response is `201`, the
  `Location` header path ends with `/api/v1/users/{id}`, the body contains `id`, `name` `"Alice"`, the email, and
  `createdAt` as an ISO-8601 UTC timestamp ending in `Z`, and the `users` table has exactly one row with that email.
- **AC-2:** Given a user created as in AC-1, then the `accounts` table has exactly one row with `user_id` = the user's ID,
  `kind = 'USER'` and `balance = 0`, and `GET /api/v1/users/{id}/account` returns `200` with that account's `id`,
  `userId` = the user's ID and `balance` `"0.00"` (a JSON string). `SELECT typeof(balance) FROM accounts WHERE user_id = ?`
  returns `'integer'`.
- **AC-3:** Given a new key, when the client registers `{"name":"  Bob  ","email":"  Bob-<unique>@Example.COM "}`, then
  the response is `201` with `name` `"Bob"` and `email` `"bob-<unique>@example.com"`, and the row in `users` stores those
  same values.
- **AC-4:** Given a new key for each case, when the client sends a create request with (a) no `name`, (b) `name` `"   "`,
  (c) a `name` of 101 characters, (d) no `email`, (e) `email` `"not-an-email"`, (f) an `email` of 255 characters, or
  (g) malformed JSON, then each response is `400` with `Content-Type: application/problem+json`, the number of rows in
  `users` and `accounts` is unchanged, and no `idempotency_keys` row exists for that key.
- **AC-5:** Given a user with email `carol-<unique>@example.com`, when the client registers another user with a new key
  and email `CAROL-<unique>@example.com`, then the response is `409` with title `Email already in use`, and `users` still
  has exactly one row with that email.
- **AC-6:** Given an existing user, when the client sends `GET /api/v1/users/{id}`, then the response is `200` with the
  same body as on creation. When the client requests an ID that does not exist, then the response is `404` with
  `Content-Type: application/problem+json`.
- **AC-7:** Given no user with a given ID, when the client sends `GET /api/v1/users/{id}/account`, then the response is
  `404` with `Content-Type: application/problem+json`.
- **AC-8:** Given at least 3 users exist, when the client sends `GET /api/v1/users?page=0&size=2&sort=id,asc`, then the
  response is `200`, `content` has 2 users in ascending `id` order, `page.size` is `2`, `page.number` is `0`,
  `page.totalElements` equals `SELECT count(*) FROM users`, and `page.totalPages` equals
  `ceil(totalElements / 2)`. When the client sends `GET /api/v1/users` with no parameters, then `page.size` is `20`.
  When the client sends `size=500`, then `page.size` is `100`. When the client sends `sort=unknownField,asc`, then the
  response is `400`.
- **AC-9:** When the client sends a valid `POST /api/v1/users` (a) without an `Idempotency-Key` header or (b) with a blank
  one, then the response is `400` with title `Idempotency-Key header required` and no user with that email exists.
- **AC-10:** When the client sends a valid `POST /api/v1/users` with an `Idempotency-Key` of (a) 256 characters or
  (b) containing a space, then the response is `400` with title `Invalid Idempotency-Key header` and no user with that
  email exists.
- **AC-11:** Given a `POST /api/v1/users` with key `K` returned `201` without an `Idempotent-Replayed` header, when the
  client sends exactly the same request with key `K` again, then the response is `201` with header
  `Idempotent-Replayed: true`, the same `Location` and a JSON body equal to the first one; `users` has exactly one row
  with that email, `accounts` has exactly one row for that user, and `idempotency_keys` has exactly one row with
  `idempotency_key = K`, `request_method = 'POST'`, `request_path = '/api/v1/users'` and `response_status = 201`.
- **AC-12:** Given a `POST /api/v1/users` with key `K` and email A returned `201`, when the client sends
  `POST /api/v1/users` with key `K` and a body with email B, then the response is `422` with title
  `Idempotency key reused` and no user with email B exists.
- **AC-13:** Given a `POST /api/v1/users` with key `K` failed with `400` (invalid body), when the client sends a valid
  request with the same key `K`, then the response is `201` without an `Idempotent-Replayed` header and the user exists.
  Likewise, given a request with key `K2` failed with `409 Email already in use`, when the client sends key `K2` with a
  different, unused email, then the response is `201`.
- **AC-14:** Given a new key `K`, when the client sends 10 identical `POST /api/v1/users` requests with key `K`
  concurrently, then `users` has exactly one row with that email, `accounts` has exactly one row for that user, every
  response is either `201` (all `201` bodies are JSON-equal) or `409` with title `Request in progress`, and at least one
  response is `201`.

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
- `src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt`

**AC coverage:**

| AC    | Test (class › method) |
|-------|-----------------------|
| AC-1  | `Story001RegisterUsersAcceptanceTest` › `AC-1 registers a user and returns 201 with Location and body` |
| AC-2  | `Story001RegisterUsersAcceptanceTest` › `AC-2 registration creates exactly one USER account with zero balance` |
| AC-3  | `Story001RegisterUsersAcceptanceTest` › `AC-3 trims name and email and stores the email in lower case` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (a) rejects a request without name with 400 and no side effects` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (b) rejects a blank name with 400 and no side effects` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (c) rejects a name of 101 characters with 400 and no side effects` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (d) rejects a request without email with 400 and no side effects` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (e) rejects an invalid email with 400 and no side effects` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (f) rejects an email of 255 characters with 400 and no side effects` |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (g) rejects malformed JSON with 400 and no side effects` |
| AC-5  | `Story001RegisterUsersAcceptanceTest` › `AC-5 rejects an email that differs only in letter case with 409` |
| AC-6  | `Story001RegisterUsersAcceptanceTest` › `AC-6 reads a user by id and returns 404 problem detail for an unknown id` |
| AC-7  | `Story001RegisterUsersAcceptanceTest` › `AC-7 returns 404 problem detail for the account of an unknown user` |
| AC-8  | `Story001RegisterUsersAcceptanceTest` › `AC-8 returns the requested page sorted by id ascending` |
| AC-8  | `Story001RegisterUsersAcceptanceTest` › `AC-8 uses page size 20 by default` |
| AC-8  | `Story001RegisterUsersAcceptanceTest` › `AC-8 caps the page size at 100` |
| AC-8  | `Story001RegisterUsersAcceptanceTest` › `AC-8 rejects an unknown sort property with 400` |
| AC-9  | `Story001RegisterUsersAcceptanceTest` › `AC-9 (a) rejects a POST without Idempotency-Key header with 400` |
| AC-9  | `Story001RegisterUsersAcceptanceTest` › `AC-9 (b) rejects a POST with a blank Idempotency-Key header with 400` |
| AC-10 | `Story001RegisterUsersAcceptanceTest` › `AC-10 (a) rejects an Idempotency-Key of 256 characters with 400` |
| AC-10 | `Story001RegisterUsersAcceptanceTest` › `AC-10 (b) rejects an Idempotency-Key containing a space with 400` |
| AC-11 | `Story001RegisterUsersAcceptanceTest` › `AC-11 replays the stored response for the same key and the same request` |
| AC-12 | `Story001RegisterUsersAcceptanceTest` › `AC-12 rejects a reused key with a different request with 422` |
| AC-13 | `Story001RegisterUsersAcceptanceTest` › `AC-13 a key whose request failed with 400 can be reused for a valid request` |
| AC-13 | `Story001RegisterUsersAcceptanceTest` › `AC-13 a key whose request failed with 409 can be reused with a different email` |
| AC-14 | `Story001RegisterUsersAcceptanceTest` › `AC-14 concurrent identical requests with the same key perform the operation at most once` |

**Red run:** `.\gradlew.bat test`: compiles; 29 tests, 25 failed. Of the 26 new tests, 25 fail for the expected reason
and 1 (AC-7) passes (see Notes). The pre-existing `KotlinTestApplicationTests`, `SqliteDialectTest` and
`VirtualThreadsTest` pass.

| Test | Failure reason (expected before implementation) |
|------|--------------------------------------------------|
| `AC-1 …`, `AC-2 …`, `AC-3 …`, `AC-5 …`, `AC-6 …`, `AC-11 …`, `AC-12 …`, `AC-13 … failed with 409 …`, `AC-8 returns the requested page …` | `expected: <201> but was: <404>`: `POST /api/v1/users` does not exist yet |
| `AC-4 (a)` … `AC-4 (g)` | `no such table: users`: the V1 migration does not exist yet |
| `AC-8 uses page size 20 by default`, `AC-8 caps the page size at 100` | `expected: <200> but was: <404>`: `GET /api/v1/users` does not exist yet |
| `AC-8 rejects an unknown sort property with 400` | `Status expected:<400> but was:<404>`: endpoint does not exist yet |
| `AC-9 (a)`, `AC-9 (b)`, `AC-10 (a)`, `AC-10 (b)` | status `404` (`No static resource api/v1/users.`) instead of `400`: endpoint and idempotency handling do not exist yet |
| `AC-13 a key whose request failed with 400 …` | `expected: <400> but was: <404>`: endpoint does not exist yet |
| `AC-14 …` | responses are `404` instead of `201` / `409 Request in progress`: endpoint does not exist yet |
| `AC-7 …` | **passes** (see Notes) |

**Notes:**
- **Isolation:** there is no `@BeforeEach` cleanup. As the story's ACs require, the database is shared and every test
  uses its own unique emails (`<prefix>-<UUID>@example.com`) and idempotency keys (UUIDs). All assertions on persisted
  state are scoped to the test's own email, key or user ID. AC-4 compares total row counts before/after its own request,
  and AC-8 compares against `SELECT count(*) FROM users` and the first two IDs, read in the same test. Tests run
  sequentially, so these counts are stable within a test.
- **AC-7 is green before implementation.** Spring Boot already answers any unmapped path with a `404`
  `application/problem+json` (`spring.mvc.problemdetails.enabled: true`). AC-7 demands only `404` + problem+json and no
  title, so asserting more would over-specify. The test is kept as a regression guard: the implementation must not
  answer an unknown user's account with `200`, an empty body or a non-problem error. The 404 half of AC-6 is in the same
  test as its creation half, so AC-6 is red.
- **Unknown ID:** `Long.MAX_VALUE` is used as an ID that no test ever creates.
- **Problem details:** every error assertion checks the status, `Content-Type` compatible with `application/problem+json`,
  the body's `status`, and the exact `title` wherever the story's error table defines one. AC-8's unknown sort property
  checks only `400`, as the AC states.
- **AC-2:** `balance` must be a JSON *string* `"0.00"` (a JSON number fails the test). The account `id` is compared with
  the `accounts.id` row of that user.
- **AC-1:** `createdAt` must be a JSON string matching `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$` and parse with
  `Instant.parse`; the exact time is not asserted. `Location` may be absolute or relative; only its path is checked.
- **AC-4 (f):** the email is exactly 255 characters and otherwise matches the email pattern, so only the length rule
  rejects it.
- **AC-9 (b):** the blank key is sent as `"   "`. HTTP strips surrounding whitespace, so the server may see an empty
  value; both count as blank.
- **AC-14:** 10 virtual threads (`Thread.ofVirtual()`, no pool) are released together by a `CountDownLatch` and send the
  identical request with the same key through `RestTestClient`. Every response must be `201` (all bodies JSON-equal) or a
  `409` problem with title `Request in progress`; at least one is `201`; exactly one user and one account exist.
- JSON bodies are compared as Jackson 3 trees (`tools.jackson.databind`), so field order does not matter.

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
| AC-1  | yes     | yes | `201`; integer `id`; `name`, `email`; `createdAt` is a JSON string, ISO-8601 UTC ending in `Z`, parsed with `Instant.parse`; `Location` path; exactly one `users` row with the email. |
| AC-2  | yes     | yes | One `accounts` row for the user with `kind = 'USER'`, `balance = 0` and `typeof(balance) = 'integer'`; `GET .../account` returns `200` with `id` = that row's ID, `userId`, and `balance` as the JSON string `"0.00"`. |
| AC-3  | yes     | yes | Trimmed `name`, trimmed and lower-cased `email`, checked in the response and in the `users` row. |
| AC-4  | yes     | yes | Seven tests for (a)–(g). Each checks `400` + problem+json, unchanged `users`/`accounts` counts, and no `idempotency_keys` row for the key. The (f) email is exactly 255 chars and otherwise valid. |
| AC-5  | yes     | yes | `409` problem with title `Email already in use`; still exactly one row with the email. |
| AC-6  | yes     | yes | `GET` body is JSON-equal to the creation body; an unknown ID returns a `404` problem. |
| AC-7  | yes     | yes | Green before implementation (see note 1). |
| AC-8  | yes     | yes | Page 0 / size 2: `content` IDs equal the first two IDs by `ORDER BY id`; `page.size`, `number`, `totalElements` (= `count(*)`), `totalPages` (= ceil). Separate tests check default size `20`, the `100` cap, and that an unknown sort property returns `400` (see T-1). |
| AC-9  | yes     | yes | (a) missing and (b) blank key: `400` problem with title `Idempotency-Key header required`; no user with the email. |
| AC-10 | yes     | yes | (a) a 256-char key of visible ASCII and (b) a key containing a space: `400` problem with title `Invalid Idempotency-Key header`; no user with the email. |
| AC-11 | yes     | yes | First response has no `Idempotent-Replayed` header. The replay is `201` with `Idempotent-Replayed: true`, the same `Location` and a JSON-equal body. Exactly one user, one account, and one `idempotency_keys` row with `POST`, `/api/v1/users` and `201`. |
| AC-12 | yes     | yes | `422` problem with title `Idempotency key reused`; no user with email B. |
| AC-13 | yes     | yes | 400-then-valid and 409-then-other-email: both return `201` without a replay header, and the user exists. |
| AC-14 | yes     | yes | 10 virtual threads released by a latch with the same key. Each response is `201` (bodies JSON-equal) or a `409` problem titled `Request in progress`, and at least one is `201`. Exactly one user and one account. A `500` fails the test. |

**Quality checks:** black-box only (no production imports; only `RestTestClient` + `JdbcTemplate`): yes ·
deterministic (no sleeps or clock assertions; AC-14 accepts every outcome R-11 allows and only bounds `join` at 60 s): yes ·
isolated test data (UUID-based emails and keys; assertions scoped to the test's own data; AC-4/AC-8 global counts are
read within the test, and test classes run sequentially): yes · AC-prefixed names, English: yes · no `@Disabled` or tags,
and no changes to `build.gradle.kts` or `src/test/resources` (checked with `git status`): yes · no over-specification
(every assertion is backed by an AC or by *Technical details*): yes · red for the expected reason (own run): yes.

**Red run (own run, `./gradlew test`, 2026-11-09):** compiles; 29 tests, 25 failed, 0 skipped.
- `KotlinTestApplicationTests`, `SqliteDialectTest` and `VirtualThreadsTest` pass.
- Expected failures, matching *Test design 1*:
  - AC-4 (a)–(g): `no such table: users`.
  - AC-1/2/3/5/6/8 (page)/11/12/13 (409 case): `expected: <201> but was: <404>`.
  - AC-8 (default size, cap) and AC-13 (400 case): `404` instead of `200` / `400`.
  - AC-8 unknown sort: `404` instead of `400`.
  - AC-9, AC-10, AC-14: `404 No static resource api/v1/users.` instead of `400` / `201` / `409`.
- AC-7 passes (see note 1).

**Notes:**
1. AC-7 is green before implementation because Boot already answers unmapped paths with a `404` problem+json, and the
   AC demands nothing more. Making it red would require over-specification (for example a title the story does not
   define). Accepted as a regression guard.
2. For the implementer: the `test` profile uses a shared-cache in-memory SQLite DB without `busy_timeout`. In
   shared-cache mode, lock conflicts between connections fail immediately with `SQLITE_LOCKED`, and the busy timeout
   does not apply to them. AC-14 can therefore hit lock errors under concurrency. Per the story's *Constraints and
   notes* and R-11, these must end as a replay or `409 Request in progress`, never `500`. This is an implementation
   concern, not a test defect.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| T-1 | minor    | `Story001RegisterUsersAcceptanceTest.kt:257` | `AC-8 rejects an unknown sort property with 400` checks only the status. AC-8 asks for no more, but *Technical details → Errors* says every error response is a `ProblemDetail`, so a `400` with an empty or HTML body would pass. Not blocking, because the AC text is met. The implementer should still return a problem+json here. |
| T-2 | minor    | `Story001RegisterUsersAcceptanceTest.kt:224` | The AC-8 page test checks `content` items only by `id`, not by their full user shape (`name`, `email`, `createdAt`). The shape is covered for single users by AC-1/AC-6. Not blocking. |

**Locked tests:**

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt` | 74793e5f2b0f3b23c556c63966af9695027ef598ba4974f9f1db3b8fd32b112a |

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

**Summary:** Implemented user registration with an automatically opened `USER` account. Added reading a user, the
user list (paged and sorted) and reading a user's account. Added the reusable building blocks for later stories:
- the `Idempotency-Key` mechanism for every `POST` under `/api/v1`: a body-caching filter, an interceptor that runs
  before validation (header checks, a JVM-wide in-flight key lock, `422` for a reused key, replay), and
  `IdempotentExecutor`, which stores the `2xx` response in the same transaction as the business change;
- the money format (`Money`);
- the page response shape (`PageResponse`, `withValidatedSort`);
- `Instant` <-> ISO-8601 UTC `TEXT` converters;
- the single `ProblemDetail` advice (`ApiExceptionHandler` + the `ApiException` base class).

**Changed files:**
- `src/main/resources/db/migration/V1__create_users_accounts_idempotency.sql`: new. Tables `users`, `accounts`,
  `idempotency_keys` and the index `ux_accounts_system_kind`, exactly as in *Technical details*.
- `src/main/resources/application.yaml`: `spring.data.web.pageable.default-page-size: 20`, `max-page-size: 100`.
- `src/main/kotlin/com/example/kotlintest/config/SqliteDialect.kt`: added the `jdbcCustomConversions` bean (SQLite
  store conversions + `Instant` <-> `TEXT`). Boot's own bean is `@ConditionalOnMissingBean`.
- `config/InstantTextConverters.kt`: new. Writes ISO-8601 UTC with exactly 3 fraction digits (sortable text); reads
  with `Instant.parse`.
- `config/ClockConfig.kt`: new. `Clock.systemUTC()` bean.
- `common/ApiException.kt`, `common/ApiExceptionHandler.kt`: new. The domain-error base class, plus the single
  `@RestControllerAdvice` (extends `ResponseEntityExceptionHandler`, with a catch-all `500` that leaks no details).
- `common/PageResponse.kt`: new. `PageResponse<T>` (`content` + `page{size, number, totalElements, totalPages}`) and
  `Pageable.withValidatedSort` (whitelist → `400`, default `id ASC`, `id` tie-breaker).
- `common/SqliteErrors.kt`: new. `Throwable.isUniqueViolation("table.column")` (SQLite code 19 + message).
- `common/money/Money.kt`: new. `parse`/`format` between money strings and `Long` minor units, using string and
  integer arithmetic with `Math.addExact`/`multiplyExact`.
- `common/idempotency/`: new. `IdempotencyKey` (header rules, SHA-256 body hash), `IdempotencyContext`,
  `IdempotencyRecord` + `IdempotencyRecordRepository`, `IdempotencyKeyLocks`, `CachedBodyFilter`,
  `IdempotencyInterceptor` + `IdempotencyWebConfig`, `IdempotentExecutor`, `IdempotencyExceptions`.
- `user/`: new. `User` (+ `UserNotFoundException`, `EmailAlreadyInUseException`), `UserRepository`, `UserService`,
  `UserDtos` (the request is trimmed in a `@JsonCreator`, so validation sees the trimmed values), `UserController`.
- `account/`: new. `Account` + `AccountKind`, `AccountRepository`, `AccountService`, `AccountDtos` (`balance` as a money
  string), `AccountController` (`GET /api/v1/users/{userId}/account`).
- `AGENTS.md`: new sections Pagination, Money format, Idempotency-Key, Errors (with the titles table); the timestamp
  convention; updated project layout.
- Tests (own, not locked): `common/money/MoneyTest.kt`, `common/idempotency/IdempotencyKeyTest.kt`,
  `user/UserPersistenceTest.kt` (`@DataJdbcTest`), `user/UserApiTest.kt`.

**Migrations:** `V1__create_users_accounts_idempotency.sql`.

**Acceptance criteria evidence:**

| AC    | Locked acceptance test (now passing) | Additional tests by the implementer |
|-------|--------------------------------------|-------------------------------------|
| AC-1  | `Story001RegisterUsersAcceptanceTest` › `AC-1 registers a user and returns 201 with Location and body` | `UserPersistenceTest` › `stores Instant as ISO-8601 UTC text with millisecond precision and reads it back` |
| AC-2  | `Story001RegisterUsersAcceptanceTest` › `AC-2 registration creates exactly one USER account with zero balance` | `MoneyTest` (all 6) |
| AC-3  | `Story001RegisterUsersAcceptanceTest` › `AC-3 trims name and email and stores the email in lower case` | — |
| AC-4  | `Story001RegisterUsersAcceptanceTest` › `AC-4 (a)` … `AC-4 (g)` (7 tests) | — |
| AC-5  | `Story001RegisterUsersAcceptanceTest` › `AC-5 rejects an email that differs only in letter case with 409` | `UserPersistenceTest` › `a duplicate email is recognized as a unique violation on users_email` |
| AC-6  | `Story001RegisterUsersAcceptanceTest` › `AC-6 reads a user by id and returns 404 problem detail for an unknown id` | `UserApiTest` › `an unknown user returns a 404 problem detail titled User not found`, `a non-numeric user id returns a 400 problem detail` |
| AC-7  | `Story001RegisterUsersAcceptanceTest` › `AC-7 returns 404 problem detail for the account of an unknown user` | — |
| AC-8  | `Story001RegisterUsersAcceptanceTest` › `AC-8 returns the requested page sorted by id ascending`, `AC-8 uses page size 20 by default`, `AC-8 caps the page size at 100`, `AC-8 rejects an unknown sort property with 400` | `UserApiTest` › `unknown sort property returns a 400 problem detail` (test review T-1), `every allowed sort property is accepted` |
| AC-9  | `Story001RegisterUsersAcceptanceTest` › `AC-9 (a) …`, `AC-9 (b) …` | `IdempotencyKeyTest` › `missing or blank header is required` |
| AC-10 | `Story001RegisterUsersAcceptanceTest` › `AC-10 (a) …`, `AC-10 (b) …` | `IdempotencyKeyTest` › `accepts 1 to 255 visible ASCII characters`, `too long or non-visible characters are invalid` |
| AC-11 | `Story001RegisterUsersAcceptanceTest` › `AC-11 replays the stored response for the same key and the same request` | `UserApiTest` › `a replayed response is served with application json` |
| AC-12 | `Story001RegisterUsersAcceptanceTest` › `AC-12 rejects a reused key with a different request with 422` | `IdempotencyKeyTest` › `body hash is the SHA-256 hex digest of the raw bytes` |
| AC-13 | `Story001RegisterUsersAcceptanceTest` › `AC-13 a key whose request failed with 400 can be reused for a valid request`, `AC-13 a key whose request failed with 409 can be reused with a different email` | — |
| AC-14 | `Story001RegisterUsersAcceptanceTest` › `AC-14 concurrent identical requests with the same key perform the operation at most once` | 8 extra reruns of the whole locked class (`--rerun`): 8/8 green, no `Unhandled exception` in the logs |

**Build:** `./gradlew clean build` (Git Bash, 2026-11-09): BUILD SUCCESSFUL, 48 tests, 0 failures, 0 errors,
0 skipped. No compiler warnings.

```
> Task :test
> Task :check
> Task :build

BUILD SUCCESSFUL in 15s
9 actionable tasks: 9 executed
name="com.example.kotlintest.KotlinTestApplicationTests" tests="1" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.acceptance.Story001RegisterUsersAcceptanceTest" tests="26" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.common.idempotency.IdempotencyKeyTest" tests="4" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.common.money.MoneyTest" tests="6" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.config.SqliteDialectTest" tests="1" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.config.VirtualThreadsTest" tests="1" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.user.UserApiTest" tests="6" skipped="0" failures="0" errors="0"
name="com.example.kotlintest.user.UserPersistenceTest" tests="3" skipped="0" failures="0" errors="0"
```

**Locked tests verify:**

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt
STORY-001-register-users-with-account-and-idempotent-post: 1/1 locked tests unchanged
```

**Deviations and notes:**
- **Concurrent same-key requests (R-11 / processing order step 5):** the in-flight check runs **before** the record
  lookup and needs no database access. When another request with the same key is still running, the answer is
  `409 Request in progress` even in the rare case where a record for the key already exists. R-11 allows that outcome
  ("replayed response or 409"). The reason: in the `test` profile, shared-cache SQLite fails table-lock conflicts at
  once with `SQLITE_LOCKED`, so a concurrent read of `idempotency_keys` could make the running write fail (see test
  review note 2). Without a concurrent same-key request, the order is exactly as specified: header → `422`/replay →
  processing.
- **Single-writer safety:** `IdempotentExecutor` inserts the `idempotency_keys` row first (placeholder status `0`)
  and fills in the response before the commit. The transaction is therefore a write transaction from its first
  statement, which avoids read-then-write `SQLITE_BUSY_SNAPSHOT` and lets `busy_timeout` apply. The placeholder is
  never visible outside the transaction.
- **Across JVMs:** the in-flight lock is per JVM. A `UNIQUE` violation on `idempotency_keys.idempotency_key` becomes
  `409 Request in progress`. A `UNIQUE` violation on `users.email` (two registrations racing with different keys)
  becomes `409 Email already in use`.
- **Error titles not defined by the story:** `404` → `User not found`; an unknown sort property → `400`
  `Invalid sort property` (also a `ProblemDetail`, which addresses test review T-1). Both are documented in AGENTS.md.
- `Location` is a relative URL (`/api/v1/users/{id}`), which the story allows.
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

| Req  | Met | Note |
|------|-----|------|
| R-1  | yes | `UserController.create` → `UserService.register`. |
| R-2  | yes | `CreateUserRequest.of` (`@JsonCreator`) trims before validation; `@NotBlank` + `@Size(max = 100)` on `name`; `@Size(max = 254)` + the story's `@Pattern` on `email`; `UserService.register` lower-cases with `Locale.ROOT`. See F-2. |
| R-3  | yes | `existsByEmail` on the lower-cased email, plus the `UNIQUE` / `CHECK (email = lower(email))` columns; a racing `UNIQUE` violation maps to `409 Email already in use` (`SqliteErrors.isUniqueViolation`). Seen live: 10 concurrent registrations of one email with different keys gave 1 × `201` and 9 × `409 Email already in use`. |
| R-4  | yes | `AccountService.openUserAccount` (kind `USER`, balance `0`) joins the transaction of `register`, which in turn joins the `IdempotentExecutor` transaction. |
| R-5  | yes | `GET /api/v1/users/{id}`; unknown ID → `UserNotFoundException` (`404 User not found`). |
| R-6  | yes | `GET /api/v1/users` with `Pageable`, `withValidatedSort` (whitelist `id`, `name`, `email`, `createdAt`), `PageResponse`; default `20` and cap `100` in `application.yaml`. |
| R-7  | yes | `AccountController` `GET /api/v1/users/{userId}/account`; missing account → `404 User not found`. |
| R-8  | yes | `IdempotencyInterceptor.preHandle` validates the header (`IdempotencyKey.validate`) before argument resolution and validation, so no side effects. |
| R-9  | yes | Replay of stored status, `Location`, body (`application/json`) and `Idempotent-Replayed: true`. Seen live: the replay is byte-identical to the first response. |
| R-10 | yes | `IdempotencyContext.matches` compares method, path (without query string) and body SHA-256 → `422 Idempotency key reused`. |
| R-11 | yes | JVM-wide in-flight set (`IdempotencyKeyLocks`) → `409 Request in progress`; `UNIQUE` on `idempotency_key` as cross-JVM backstop. The in-flight check runs before the record lookup (documented deviation from the processing-order list; R-11 explicitly allows either outcome). Seen live on the file DB (WAL): 20 concurrent same-key requests created exactly one user. |
| R-12 | yes | The record is written only inside `IdempotentExecutor`, which runs after validation; any exception rolls the whole transaction back; non-`2xx` results are refused by `check`. |
| R-13 | yes | `IdempotentExecutor.execute`: reserve insert, business operation and record update in one `TransactionTemplate` transaction. |
| R-14 | yes | `AccountResponse.balance` is `Money.format(balance)` (a JSON string); `accounts.balance` is `INTEGER`. |
| R-15 | yes | `Money` uses string parsing and `Math.multiplyExact` / `addExact` on `Long`. `grep` for `Double`, `Float`, `REAL`, `NUMERIC` in `src/main` and `src/test` finds only a KDoc mention in `Money.kt`. `MoneyTest` covers `"0.29"`, `"0.57"`, `"1.15"`, `"10000000.00"` and back, and `Long` bounds. |

**Acceptance criteria:**

| AC    | Result | How checked |
|-------|--------|-------------|
| AC-1  | pass   | Locked test passed in my build; live `POST` returned `201`, relative `Location: /api/v1/users/1`, trimmed body with `createdAt` `...Z`. |
| AC-2  | pass   | Locked test passed; live `GET /api/v1/users/1/account` → `{"id":1,"userId":1,"balance":"0.00"}`. |
| AC-3  | pass   | Locked test passed; live `" Zed "` / `" Zed-…@Example.com"` stored and returned as `Zed` / `zed-…@example.com`. |
| AC-4  | pass   | 7 locked tests passed; code check: validation fails before the executor, so no rows and no key record. Live: empty body and non-string email → `400` problem+json. |
| AC-5  | pass   | Locked test passed; code check of `existsByEmail` + `UNIQUE` mapping. |
| AC-6  | pass   | Locked test passed; `UserApiTest` additionally checks the `User not found` title and a `400` for a non-numeric ID. |
| AC-7  | pass   | Locked test passed; live unknown account → `404` `application/problem+json`, title `User not found` (a real domain 404, not only Boot's unmapped-path 404). |
| AC-8  | pass   | 4 locked tests passed; live `size=500` → `page.size` `100`; `sort=version,asc` → `400 Invalid sort property` problem (addresses test review T-1); multi-property sort → `200`. |
| AC-9  | pass   | Locked tests passed; live missing key → `400 Idempotency-Key header required`. |
| AC-10 | pass   | Locked tests passed; `IdempotencyKeyTest` covers 255/256 chars, space, tab, non-ASCII, DEL. |
| AC-11 | pass   | Locked test passed; live replay identical, with `Idempotent-Replayed: true`; a query string does not change the fingerprint (replayed). |
| AC-12 | pass   | Locked test passed; live different body with the same key → `422 Idempotency key reused`. |
| AC-13 | pass   | Locked tests passed; code check: failures leave no record (rollback / validation before the executor). |
| AC-14 | pass   | Locked test passed in my build, with 0 `Unhandled exception` log lines in the whole test run; live concurrency on the file DB as described under R-11. 30 concurrent registrations with different keys: 30 × `201`, no `500`. |

**Build:** `./gradlew clean build` (Git Bash, 2026-11-09, run by the reviewer): BUILD SUCCESSFUL in 15s, no Kotlin
compiler warnings. Test reports: 48 tests, 0 failures, 0 errors, 0 skipped.
`Story001RegisterUsersAcceptanceTest` 26/26 (26 `<testcase>` entries, none skipped), `KotlinTestApplicationTests` 1,
`SqliteDialectTest` 1, `VirtualThreadsTest` 1, `IdempotencyKeyTest` 4, `MoneyTest` 6, `UserApiTest` 6,
`UserPersistenceTest` 3. The app was also started with `./gradlew bootRun` (port 18080, file DB in WAL mode): Flyway
applied V1, the smoke and concurrency checks above passed, and the log had no `Unhandled exception`; the app was
stopped afterwards. `/actuator/env` → `404` (only `health` and `info` exposed).

**Locked tests verify:** `python scripts/locked-tests.py verify STORY-001`, exit code 0:

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt
STORY-001-register-users-with-account-and-idempotent-post: 1/1 locked tests unchanged
```

No `@Disabled`, assumptions or tags anywhere in `src/test`; `build.gradle.kts` is unchanged (only `useJUnitPlatform()`,
no filters); `src/test/resources` is unchanged (`git status` / `git diff`). No earlier story has locked tests.

**Conventions:** V1 migration matches *Technical details* exactly (`STRICT`, `INTEGER PRIMARY KEY`, FK, `CHECK`s);
timestamps are ISO-8601 UTC `TEXT` with 3 fraction digits, with a `@DataJdbcTest` round trip (`UserPersistenceTest`);
`201` + `Location`, `ProblemDetail` for every error (single `@RestControllerAdvice` with a non-leaking `500`);
DTOs separate from entities; constructor injection; no `!!`; plain blocking code (no reactive types or coroutines);
English everywhere. `AGENTS.md` was updated with the idempotency, pagination, money, error-title and timestamp
conventions, as the story requires.

**Scope:** nothing from *Out of scope*. The extra error titles (`User not found`, `Invalid sort property`) fill gaps the
story left open and are documented in `AGENTS.md`. *Changed files* in Attempt 1 matches `git status`.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| F-1 | minor    | `src/main/kotlin/com/example/kotlintest/common/idempotency/CachedBodyFilter.kt:28` | Every `POST /api/v1/**` body is read into memory with `readAllBytes()` without a size limit, before the `Idempotency-Key` check. A client can make the server buffer arbitrarily large bodies. Expected (follow-up story): reject bodies above a fixed limit (for example `413` problem detail) before buffering, or check `Content-Length` first. |
| F-2 | minor    | `src/main/kotlin/com/example/kotlintest/user/UserDtos.kt:16`, `:20` | `@Size(max = 100)` / `@Size(max = 254)` count UTF-16 code units, while R-2 says "characters": a `name` of 51–100 supplementary characters (for example emoji) is rejected with `400` although it has at most 100 characters. Expected: count code points (`String.codePointCount`) or state in the story that the limit is in UTF-16 units. |

Both findings are minor and do not block completion; they should go into a follow-up story with `related: [STORY-001]`.
