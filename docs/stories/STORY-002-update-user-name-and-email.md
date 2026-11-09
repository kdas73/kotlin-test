---
id: STORY-002
title: Update user name and email
status: Completed
created: 2026-11-08
updated: 2026-11-09
depends_on: [STORY-001, STORY-009]
related: []
---

# STORY-002: Update user name and email

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |
| 2026-11-09 | Pending | Test Writing | test-writer | Writing acceptance tests |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 1 ready |
| 2026-11-09 | Test Review | Test Writing | test-reviewer | Changes requested: T-1, T-2 |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 2 ready (T-1 fixed) |
| 2026-11-09 | Test Review | Ready | test-reviewer | Tests locked |
| 2026-11-09 | Ready | In Progress | implementer | Implementation started |
| 2026-11-09 | In Progress | In Review | implementer | Attempt 1: build green, locked tests pass |
| 2026-11-09 | In Review | Completed | reviewer | Review 1: Approved |

## Context

Users registered in STORY-001 need to be able to correct their name or change their email. The update must be
idempotent like every interface of the shop: it sets absolute values, so sending the same request twice gives the same
result and needs no `Idempotency-Key`.

## Requirements

- **R-1:** A client can partially update a user's `name` and/or `email`. Fields that are absent or `null` stay unchanged.
- **R-2:** A request must change at least one field: a body with neither `name` nor `email` (or both `null`) is rejected
  with `400`.
- **R-3:** The same validation and normalization as on registration apply (STORY-001 R-2): `name` trimmed, 1–100
  characters; `email` trimmed, valid, at most 254 characters, stored in lower case. Lengths are counted in Unicode code
  points (STORY-009).
- **R-4:** Changing the email to one used by another user (in any letter case) is rejected with `409`. Changing a user's
  email to their own email in a different letter case is allowed.
- **R-5:** Repeating the same update gives the same `200` response and leaves the user unchanged. The endpoint does not
  require an `Idempotency-Key` header and does not record one.
- **R-6:** Updating an unknown user returns `404`.

## Out of scope

- Deleting users.
- Changing a user's account or balance.
- Optimistic-locking or `If-Match` headers for users.

## Technical details

### API

| Method  | Path                 | Request body        | Success response | Error responses     |
|---------|----------------------|---------------------|------------------|---------------------|
| `PATCH` | `/api/v1/users/{id}` | `{name?, email?}`   | `200` + user     | `400`, `404`, `409` |

**Request** (`Content-Type: application/json`):

```json
{ "name": "Alice Smith", "email": "alice.smith@example.com" }
```

Either field may be absent or `null` (= unchanged).

**Response** `200`: the full user, same shape as in STORY-001:

```json
{ "id": 1, "name": "Alice Smith", "email": "alice.smith@example.com", "createdAt": "2026-11-08T12:00:00.123Z" }
```

**Errors** (`ProblemDetail`, `Content-Type: application/problem+json`):
- `400`: empty change (no non-null field), invalid `name` or `email`, malformed JSON.
- `404`: unknown user ID.
- `409`, title `Email already in use`: the email belongs to another user.

### Data model

No migration. Uses `users` from STORY-001.

### Affected code

- `user/`: `UserController.kt` (new endpoint), `UserService.kt` (update logic), `UserDtos.kt` (update request DTO).
- `common/`: reuse the existing exception mapping (`Email already in use`).
- `common/validation/`: reuse the code-point length constraint from STORY-009 for `name` and `email` (not `@Size`).

### Constraints and notes

- The duplicate-email check must also rely on the `UNIQUE` constraint on `users.email`, so concurrent updates can't
  create duplicates. A constraint violation is mapped to `409 Email already in use`, never `500`.
- An `Idempotency-Key` header sent with `PATCH` is ignored.
- 2026-11-09 (story-writer, from STORY-001 Review 1 F-2): lengths are counted in Unicode code points, as decided in
  STORY-009; this story now depends on STORY-009 and has AC-9.

## Acceptance criteria

Tests share one database, so every test uses its own unique emails.

- **AC-1:** Given a user `{"name":"Alice","email":"alice-<unique>@example.com"}`, when the client sends
  `PATCH /api/v1/users/{id}` without an `Idempotency-Key` header and with `{"name":"  Alice Smith "}`, then the response
  is `200` with `name` `"Alice Smith"` and the original email, and the `users` row has the same values.
- **AC-2:** Given a user, when the client sends `PATCH` with `{"email":" New-<unique>@Example.com "}`, then the response
  is `200` with `email` `"new-<unique>@example.com"`, the name is unchanged, and the `users` row stores the new email.
- **AC-3:** Given a user, when the client sends `PATCH` with both `name` and `email`, then both are updated in the
  response and in the database, and `id` and `createdAt` are unchanged.
- **AC-4:** Given a `PATCH` with a body `B` returned `200`, when the client sends the same `PATCH` with body `B` again,
  then the response is `200` with a JSON body equal to the first one, the `users` row is unchanged, and the
  `idempotency_keys` table has no new rows.
- **AC-5:** Given a user, when the client sends `PATCH` with (a) `{}`, (b) `{"name":null,"email":null}`, (c)
  `{"name":"   "}`, (d) a `name` of 101 characters, (e) `{"email":"not-an-email"}`, or (f) malformed JSON, then each
  response is `400` with `Content-Type: application/problem+json` and the `users` row is unchanged.
- **AC-6:** Given two users A and B, when the client patches B with A's email in upper case, then the response is `409`
  with title `Email already in use` and B's row is unchanged.
- **AC-7:** Given a user with email `dave-<unique>@example.com`, when the client patches that user with
  `{"email":"DAVE-<unique>@EXAMPLE.COM"}`, then the response is `200` and the email is still
  `"dave-<unique>@example.com"`.
- **AC-8:** Given no user with a given ID, when the client sends `PATCH /api/v1/users/{id}` with a valid body, then the
  response is `404` with `Content-Type: application/problem+json`.
- **AC-9:** Given a user, when the client sends `PATCH /api/v1/users/{id}` with `name` = 100 × `😀` (U+1F600, one code
  point and two UTF-16 units each), then the response is `200` with that `name` and the `users` row stores it. When the
  client then sends `PATCH` with `name` = 101 × `😀`, then the response is `400` with
  `Content-Type: application/problem+json` and the `users` row still has the 100 × `😀` name.

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
- `src/test/kotlin/com/example/kotlintest/acceptance/Story002UpdateUserAcceptanceTest.kt`

**AC coverage:**

| AC   | Test (class › method) |
|------|-----------------------|
| AC-1 | `Story002UpdateUserAcceptanceTest` › `AC-1 trims and updates the name without an Idempotency-Key and keeps the email` |
| AC-2 | `Story002UpdateUserAcceptanceTest` › `AC-2 trims and lower-cases a new email and keeps the name` |
| AC-3 | `Story002UpdateUserAcceptanceTest` › `AC-3 updates name and email together and keeps id and createdAt` |
| AC-4 | `Story002UpdateUserAcceptanceTest` › `AC-4 repeating the same PATCH returns the same 200 body and changes nothing` |
| AC-5 | `Story002UpdateUserAcceptanceTest` › `AC-5 (a) rejects an empty object ...`, `AC-5 (b) rejects null name and null email ...`, `AC-5 (c) rejects a blank name ...`, `AC-5 (d) rejects a name of 101 characters ...`, `AC-5 (e) rejects an invalid email ...`, `AC-5 (f) rejects malformed JSON ...` (each `... with 400 and leaves the user unchanged`) |
| AC-6 | `Story002UpdateUserAcceptanceTest` › `AC-6 rejects another user's email in upper case with 409 and leaves the user unchanged` |
| AC-7 | `Story002UpdateUserAcceptanceTest` › `AC-7 accepts the user's own email in a different letter case` |
| AC-8 | `Story002UpdateUserAcceptanceTest` › `AC-8 returns 404 problem detail for an unknown user` |
| AC-9 | `Story002UpdateUserAcceptanceTest` › `AC-9 accepts a name of 100 emoji and rejects 101 emoji leaving the row unchanged` |

**Red run:** `python3 scripts/app.py test` (wraps `./gradlew test`): compiles; 73 tests, 14 new tests fail, all 59 other
tests pass (including the locked STORY-001 and STORY-009 acceptance tests).

| Test | Failure reason (expected before implementation) |
|------|--------------------------------------------------|
| `AC-1 ...` | `405 Method Not Allowed` ("Method 'PATCH' is not supported."), expected `200`: endpoint does not exist yet |
| `AC-2 ...` | `405`, expected `200`: endpoint does not exist yet |
| `AC-3 ...` | `405`, expected `200`: endpoint does not exist yet |
| `AC-4 ...` | `405` on the first PATCH, expected `200`: endpoint does not exist yet |
| `AC-5 (a)` … `AC-5 (f)` (6 tests) | `405`, expected `400`: endpoint does not exist yet |
| `AC-6 ...` | `405`, expected `409`: endpoint does not exist yet |
| `AC-7 ...` | `405`, expected `200`: endpoint does not exist yet |
| `AC-8 ...` | `405`, expected `404`: endpoint does not exist yet |
| `AC-9 ...` | `405` on the 100-emoji PATCH, expected `200`: endpoint does not exist yet |

**Notes:**
- Test data: every test inserts its own users with plain SQL (`INSERT INTO users (name, email, created_at)`, fixed
  `created_at` `2026-11-08T12:00:00.123Z`) with unique UUID-based emails, already trimmed and lower case. No table cleanup,
  because the in-memory DB is shared and `accounts` references `users`.
- No request sends an `Idempotency-Key` header (AC-1 states it; R-5 says the endpoint doesn't require one).
- "Row unchanged" is checked by comparing the full row (`id`, `name`, `email`, `created_at`) before and after.
- AC-3: `createdAt` is compared as an `Instant` (not as a formatted string) with the inserted value; the DB column must
  keep its exact text.
- AC-4: body `B` = `{"name":"  Eve Adams ","email":" Eve-<unique>@Example.com "}`, so normalization runs on both calls.
  The `idempotency_keys` row count is taken before the first PATCH and compared after the second (test classes run
  sequentially; there is no parallel test execution).
- AC-5 (d): name of 101 ASCII `N`. AC-5 (f): body `{"name":"Alice"` (unterminated object).
- AC-7: the request uses the upper-cased email including the upper-cased UUID part.
- AC-8: unknown ID is `Long.MAX_VALUE`; the test asserts first that no such row exists.
- AC-9: bodies are sent as UTF-8 bytes with the emoji written literally (as in STORY-009 tests).
- The "`Idempotency-Key` on PATCH is ignored" note in Technical details has no AC, so it is not tested.

### Test design 2 — 2026-11-09

**Test review findings addressed:**
- T-1: `body()` helper now uses `result.responseBodyContent.toString(Charsets.UTF_8)` without the unnecessary safe call
  and `orEmpty()`. `./gradlew compileTestKotlin --rerun` reports no warning for `Story002UpdateUserAcceptanceTest.kt`;
  the only remaining test-compile warning is the known one in the locked `Story009CodePointLengthAcceptanceTest.kt:146`
  (out of scope, see Test review 1 notes).
- T-2: no change, as the review states (minor; no AC requires a request with an `Idempotency-Key` header).

**Files:**
- `src/test/kotlin/com/example/kotlintest/acceptance/Story002UpdateUserAcceptanceTest.kt` (only the `body()` helper changed)

**AC coverage:** unchanged from Test design 1.

**Red run:** `python3 scripts/app.py test`: compiles; 73 tests, the same 14 new tests fail, all 59 other tests pass
(including the locked STORY-001 and STORY-009 acceptance tests). Every failure is still `405 Method Not Allowed`
("Method 'PATCH' is not supported.") instead of the expected `200` / `400` / `404` / `409`, because the endpoint does
not exist yet (same per-test reasons as in Test design 1).

**Notes:** no other changes to the tests.

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

### Test review 1 — 2026-11-09 — Verdict: Changes requested

**AC coverage:**

| AC   | Covered | Assertion matches Given/When/Then | Note |
|------|---------|-----------------------------------|------|
| AC-1 | yes     | yes                               | No `Idempotency-Key` sent; trimmed `name` and original `email` checked in the response and in the `users` row. |
| AC-2 | yes     | yes                               | Trimmed and lower-cased email in the response and row; name unchanged in both. |
| AC-3 | yes     | yes                               | Both fields in response and row; `id` (integer, same value) and `createdAt` (same instant; row text unchanged) checked. |
| AC-4 | yes     | yes                               | Same body `B` twice (normalization on both fields); JSON trees equal, full row unchanged, `idempotency_keys` count unchanged. |
| AC-5 | yes     | yes                               | (a)–(f) are separate tests; each checks `400`, `application/problem+json`, `status` field and the full row unchanged. |
| AC-6 | yes     | yes                               | B patched with A's email upper-cased; `409`, problem+json, title `Email already in use`, B's full row unchanged. |
| AC-7 | yes     | yes                               | Own email fully upper-cased (incl. UUID part); `200`, lower-case email in response and row. |
| AC-8 | yes     | yes                               | `Long.MAX_VALUE`, asserted absent first; `404` with problem+json. |
| AC-9 | yes     | yes                               | 100 × U+1F600 accepted and stored; 101 × rejected with `400` problem+json; row keeps the 100-emoji name. Code-point counts asserted. |

**Quality checks:** black-box only (no production imports; only `RestTestClient` and `JdbcTemplate`): yes ·
deterministic (UUID-based emails, fixed `created_at`, no sleeps or clock use): yes · isolated test data (own users per
test; extra rows don't break other tests' relative counts): yes · AC-prefixed English names: yes · no `@Disabled` or
excluding tags: yes · no changes to `build.gradle.kts` or `src/test/resources`: yes · no over-specification: yes ·
red for the expected reason (own run): yes · compiles without warnings: **no** (see T-1).

**Red run (own):** `python3 scripts/app.py test --rerun`: compiles; 73 tests, 14 failed. All 14 failures are in
`Story002UpdateUserAcceptanceTest`, each with `405 Method Not Allowed` ("Method 'PATCH' is not supported."), because the
endpoint doesn't exist yet. All 59 other tests pass, including the locked STORY-001 and STORY-009 acceptance tests.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| T-1 | blocking | `Story002UpdateUserAcceptanceTest.kt:263` | The test file adds a compiler warning (`./gradlew compileTestKotlin --rerun`): `w: ...Story002UpdateUserAcceptanceTest.kt:263:35 Unnecessary safe call on a non-null receiver of type 'ByteArray'.` AGENTS.md (definition of done) requires `./gradlew build` with no new warnings. Once the file is locked, the implementer can't remove this warning, so it would become a permanent DoD violation. Fix: use `result.responseBodyContent.toString(Charsets.UTF_8)` (no `?.` / `orEmpty()`), and recompile to confirm there are no warnings. |
| T-2 | minor    | `Story002UpdateUserAcceptanceTest.kt` (whole class) | The note in Technical details ("An `Idempotency-Key` header sent with `PATCH` is ignored") and the "does not record one" part of R-5 are only exercised without a header. No AC requires a request with a header, so this does not block approval and needs no change. The reviewer should check this behaviour during the implementation review. |

**Notes:** The locked test `Story009CodePointLengthAcceptanceTest.kt:146` has the same warning. STORY-009 is
`Completed` and out of scope for this review. Fixing it needs a follow-up story (user's decision).

### Test review 2 — 2026-11-09 — Verdict: Approved

**Previous findings:**
- T-1 (blocking): fixed. `body()` now uses `result.responseBodyContent.toString(Charsets.UTF_8)`.
  `./gradlew compileTestKotlin --rerun` reports no warning for `Story002UpdateUserAcceptanceTest.kt`. The only
  test-compile warning left is the known one in the locked `Story009CodePointLengthAcceptanceTest.kt:146` (STORY-009,
  out of scope).
- T-2 (minor): no change needed, as stated in Test review 1. The reviewer checks during the implementation review that
  an `Idempotency-Key` sent with `PATCH` is ignored.

I re-read the whole file. Apart from line 263 (the `body()` helper), it is identical to the version reviewed in Test
review 1, so the AC coverage table there still applies.

**AC coverage:**

| AC   | Covered | Assertion matches Given/When/Then | Note |
|------|---------|-----------------------------------|------|
| AC-1 | yes     | yes                               | Unchanged since Test review 1 |
| AC-2 | yes     | yes                               | Unchanged since Test review 1 |
| AC-3 | yes     | yes                               | Unchanged since Test review 1 |
| AC-4 | yes     | yes                               | Unchanged since Test review 1 |
| AC-5 | yes     | yes                               | (a)–(f), six tests; unchanged since Test review 1 |
| AC-6 | yes     | yes                               | Unchanged since Test review 1 |
| AC-7 | yes     | yes                               | Unchanged since Test review 1 |
| AC-8 | yes     | yes                               | Unchanged since Test review 1 |
| AC-9 | yes     | yes                               | Unchanged since Test review 1 |

**Quality checks:** black-box only (no production imports): yes · deterministic: yes · isolated test data: yes ·
AC-prefixed English names: yes · no `@Disabled` or excluding tags: yes · no changes to `build.gradle.kts` or
`src/test/resources`: yes · no over-specification: yes · red for the expected reason (own run): yes · compiles without
warnings: yes.

**Red run (own):** `python3 scripts/app.py test --rerun`: compiles; 73 tests, 14 failed. All 14 failures are in
`Story002UpdateUserAcceptanceTest`, each with `405 Method Not Allowed` ("Method 'PATCH' is not supported."), because the
endpoint doesn't exist yet. All 59 other tests pass, including the locked STORY-001 and STORY-009 acceptance tests.

**Findings:** none.

**Locked tests:**

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/Story002UpdateUserAcceptanceTest.kt` | bb9dc7ac3b554137041c1ed4ad83570a685b1d1baa976046bde30bee733d273c |

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

**Summary:** Added `PATCH /api/v1/users/{id}` for a partial update of `name` and/or `email`.
- **Request DTO:** `UpdateUserRequest` has nullable fields. Non-null values are trimmed while the JSON is read. The
  code-point length and email-pattern constraints are the same as on registration. An `@AssertTrue` rule rejects a body
  without any non-null field.
- **Service:** `UserService.update` returns `404` for an unknown user and stores the email in lower case. An email that
  another user has, in any letter case, is rejected with `409`, first by the `existsByEmail` check and then by mapping
  the `UNIQUE` violation on `users.email` as a backstop. A user's own email in another case never conflicts. Setting the
  current values is a no-op without a write.
- **Idempotency:** the endpoint does not use `IdempotentExecutor`, and `IdempotencyInterceptor` only handles `POST`.
  So no `Idempotency-Key` is required, and a key that is sent is ignored and not recorded.

**Changed files:**
- `src/main/kotlin/com/example/kotlintest/user/UserDtos.kt`: new `UpdateUserRequest`
- `src/main/kotlin/com/example/kotlintest/user/UserService.kt`: new `update(id, name, email)`
- `src/main/kotlin/com/example/kotlintest/user/UserController.kt`: new `@PatchMapping("/{id}")`
- `src/test/kotlin/com/example/kotlintest/user/UserApiTest.kt`: 4 PATCH tests added (not locked)
- `src/test/kotlin/com/example/kotlintest/user/UserServiceTest.kt`: new unit tests for `UserService.update` (not locked)

**Migrations:** none (uses `users` from `V1__create_users_accounts_idempotency.sql`).

**Acceptance criteria evidence:**

| AC   | Locked acceptance test (now passing) | Additional tests by the implementer |
|------|--------------------------------------|-------------------------------------|
| AC-1 | `Story002UpdateUserAcceptanceTest` › `AC-1 trims and updates the name without an Idempotency-Key and keeps the email` | `UserServiceTest` › `null arguments keep the current values` |
| AC-2 | `Story002UpdateUserAcceptanceTest` › `AC-2 trims and lower-cases a new email and keeps the name` | — |
| AC-3 | `Story002UpdateUserAcceptanceTest` › `AC-3 updates name and email together and keeps id and createdAt` | — |
| AC-4 | `Story002UpdateUserAcceptanceTest` › `AC-4 repeating the same PATCH returns the same 200 body and changes nothing` | `UserServiceTest` › `setting the current values does not write`; `UserApiTest` › `PATCH ignores an Idempotency-Key header and records nothing` |
| AC-5 | `Story002UpdateUserAcceptanceTest` › `AC-5 (a)` … `AC-5 (f)` (6 tests) | `UserApiTest` › `PATCH with an email longer than 254 code points returns a 400 problem detail`, `PATCH with a non-numeric user id returns a 400 problem detail` |
| AC-6 | `Story002UpdateUserAcceptanceTest` › `AC-6 rejects another user's email in upper case with 409 and leaves the user unchanged` | `UserServiceTest` › `a UNIQUE violation on save is mapped to EmailAlreadyInUseException`, `other data access errors on save are not mapped` |
| AC-7 | `Story002UpdateUserAcceptanceTest` › `AC-7 accepts the user's own email in a different letter case` | — |
| AC-8 | `Story002UpdateUserAcceptanceTest` › `AC-8 returns 404 problem detail for an unknown user` | `UserApiTest` › `PATCH of an unknown user returns a 404 problem detail titled User not found`; `UserServiceTest` › `an unknown user throws UserNotFoundException` |
| AC-9 | `Story002UpdateUserAcceptanceTest` › `AC-9 accepts a name of 100 emoji and rejects 101 emoji leaving the row unchanged` | — |

**Build:** `python3 scripts/app.py build` (wraps `./gradlew build`):

```
BUILD SUCCESSFUL in 12s
  com.example.kotlintest.acceptance.Story001RegisterUsersAcceptanceTest: 26 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story002UpdateUserAcceptanceTest: 14 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story009CodePointLengthAcceptanceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.idempotency.IdempotencyKeyTest: 4 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.money.MoneyTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.validation.CodePointLengthTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.config.SqliteDialectTest: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.config.VirtualThreadsTest: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserApiTest: 10 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserPersistenceTest: 3 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserServiceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  TOTAL: 82 tests, 0 failures, 0 errors, 0 skipped
build: SUCCESSFUL
```

The red run before the implementation (`python3 scripts/app.py test --story 002`) had 14 tests and 14 failures, all
`405 Method Not Allowed`.

Warnings: `./gradlew compileKotlin compileTestKotlin --rerun` reports only the known warning in the locked
`Story009CodePointLengthAcceptanceTest.kt:146:35` (`Unnecessary safe call on a non-null receiver of type 'ByteArray'`).
There are no new warnings.

**Locked tests verify:**

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt
STORY-001-register-users-with-account-and-idempotent-post: 1/1 locked tests unchanged
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story002UpdateUserAcceptanceTest.kt
STORY-002-update-user-name-and-email: 1/1 locked tests unchanged
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt
STORY-009-count-text-length-in-code-points: 1/1 locked tests unchanged
```

**Deviations and notes:**
- No test defects.
- T-2 (Test review 1): `UserApiTest` › `PATCH ignores an Idempotency-Key header and records nothing` sends an invalid
  key with `PATCH`. The response is `200`, and the `idempotency_keys` row count is unchanged.
- The empty-change rule (R-2) is a bean-validation constraint on the DTO, so it gives the same `400` problem detail as
  any other validation error.

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

**Changes reviewed** (`git status` / `git diff`): `UserController.kt` (`@PatchMapping("/{id}")`), `UserService.kt`
(`update`), `UserDtos.kt` (`UpdateUserRequest`), `UserApiTest.kt` (4 PATCH tests), new `UserServiceTest.kt`, new locked
`Story002UpdateUserAcceptanceTest.kt`, and this story file. No migration, no changes to `build.gradle.kts`,
`src/test/resources`, `application.yaml` or other locked tests. This matches *Changed files* in Attempt 1.

**Requirements:**

| Req | Met | Note |
|-----|-----|------|
| R-1 | yes | `UpdateUserRequest` has nullable `name`/`email`; `UserService.update` keeps the current value for `null` (`name ?: user.name`, `normalizedEmail ?: user.email`). |
| R-2 | yes | `@get:AssertTrue isChangeRequested` rejects `{}` and `{"name":null,"email":null}` with `400` problem detail (AC-5 a/b; own curl check). |
| R-3 | yes | `@JsonCreator` trims non-null values; `@field:CodePointLength(min = 1, max = 100)` on `name`, `CodePointLength(min = 1, max = 254)` + `EMAIL_PATTERN` on `email` (same pattern constant as `CreateUserRequest`); email lower-cased with `Locale.ROOT` in the service. Code-point counting via `common/validation/CodePointLength` (not `@Size`). |
| R-4 | yes | `existsByEmail(normalizedEmail)` only when the email differs from the user's own, so the own email in another case is allowed; the `UNIQUE` violation on `users.email` (`V1`: `email TEXT NOT NULL UNIQUE`) is mapped to `EmailAlreadyInUseException` (`409 Email already in use`) as a backstop; other data-access errors are rethrown. |
| R-5 | yes | Absolute values; `update` returns without a write when nothing changes. The PATCH handler doesn't use `IdempotentExecutor`, and `IdempotencyInterceptor` returns early for non-`POST` methods (`IdempotencyInterceptor.kt:35`), so no key is required or recorded. Own curl: a PATCH with an `Idempotency-Key` that was already used by a `POST` returned `200` twice with identical bodies. |
| R-6 | yes | `get(id)` throws `UserNotFoundException` → `404 User not found` problem detail. |

**Acceptance criteria:**

| AC   | Result | How checked |
|------|--------|-------------|
| AC-1 | pass | Locked test passed in my `--rerun`; code path: trim in `@JsonCreator`, email kept. |
| AC-2 | pass | Locked test; trim + `lowercase(Locale.ROOT)`; name kept. |
| AC-3 | pass | Locked test; `user.copy(...)` keeps `id` and `createdAt`. |
| AC-4 | pass | Locked test; no-op path returns the same user; no `idempotency_keys` writes on PATCH. Own curl repeat gave identical bodies. |
| AC-5 | pass | Locked tests (a)–(f); own curl: `{}`, `null` body, missing body, blank email → `400 application/problem+json`. |
| AC-6 | pass | Locked test; own curl: other user's email in upper case → `409`, title `Email already in use`. |
| AC-7 | pass | Locked test; own curl: own email upper-cased → `200`, email still lower case. |
| AC-8 | pass | Locked test; own curl: unknown ID → `404 application/problem+json`, title `User not found`. |
| AC-9 | pass | Locked test; `CodePointLength` counts code points, so 100 × U+1F600 passes and 101 fails. |

No special-casing of test inputs was found; the implementation is general.

**Build:** `python3 scripts/app.py build` (wraps `./gradlew build`): `BUILD SUCCESSFUL` (tasks up to date). Forced test run
`python3 scripts/app.py test --rerun`: **82 tests, 0 failures, 0 errors, 0 skipped**, including
`Story001RegisterUsersAcceptanceTest` 26/26, `Story002UpdateUserAcceptanceTest` 14/14,
`Story009CodePointLengthAcceptanceTest` 5/5, and the guard tests `KotlinTestApplicationTests`, `SqliteDialectTest`,
`VirtualThreadsTest`. `./gradlew compileKotlin compileTestKotlin --rerun`: the only warning is the known one in the
locked `Story009CodePointLengthAcceptanceTest.kt:146:35` (STORY-009, out of scope); no new warnings.

**Locked tests verify:**

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story002UpdateUserAcceptanceTest.kt
STORY-002-update-user-name-and-email: 1/1 locked tests unchanged
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt
STORY-001-register-users-with-account-and-idempotent-post: 1/1 locked tests unchanged
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt
STORY-009-count-text-length-in-code-points: 1/1 locked tests unchanged
```

No `@Disabled`, assumptions or excluding tags in `acceptance/`; no test filters in `build.gradle.kts`; no changes to
`src/test/resources` or test profiles.

**Conventions:** no migration needed (none added); thin controller, `@Transactional` on the service method; request DTO
separate from the entity, response via `UserResponse`; Jakarta validation with `@Valid`; errors as `ProblemDetail` via the
existing `ApiException` mapping; no `!!`; plain blocking code; English everywhere. The implementer's extra tests are
meaningful: the `UNIQUE`-violation backstop, rethrowing other errors, the no-write path, PATCH with an `Idempotency-Key`
(the T-2 follow-up from Test review 1), the 255-code-point email, and a non-numeric ID.

**Scope:** nothing from *Out of scope* (no delete, no account/balance changes, no `If-Match`/optimistic locking); no
unrelated changes.

**Findings:** none.

**Notes (no action needed):**
- Evidence accuracy: the build summary pasted in Attempt 1 omits the `KotlinTestApplicationTests: 1 tests` line, but its
  `TOTAL: 82 tests` matches my run.
- A JSON number for `name` (e.g. `{"name":123}`) is coerced to the string `"123"` by Jackson. `POST /api/v1/users` does the
  same, so the update is consistent with registration (R-3). If stricter typing is wanted, that is a follow-up story for
  both endpoints.
