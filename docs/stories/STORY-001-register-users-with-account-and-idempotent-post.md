---
id: STORY-001
title: Register users with an account and idempotent POST
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: []
related: []
---

# STORY-001: Register users with an account and idempotent POST

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

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
