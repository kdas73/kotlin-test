---
id: STORY-002
title: Update user name and email
status: Pending
created: 2026-11-08
updated: 2026-11-08
depends_on: [STORY-001]
related: []
---

# STORY-002: Update user name and email

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-08 | —    | Pending | story-writer | Story created |

## Context

Users registered in STORY-001 need to be able to correct their name or change their email. The update must be
idempotent like every interface of the shop: it sets absolute values, so sending the same request twice gives the same
result and needs no `Idempotency-Key`.

## Requirements

- **R-1:** A client can partially update a user's `name` and/or `email`. Fields that are absent or `null` stay unchanged.
- **R-2:** A request must change at least one field: a body with neither `name` nor `email` (or both `null`) is rejected
  with `400`.
- **R-3:** The same validation and normalization as on registration apply (STORY-001 R-2): `name` trimmed, 1–100
  characters; `email` trimmed, valid, at most 254 characters, stored in lower case.
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

### Constraints and notes

- The duplicate-email check must also rely on the `UNIQUE` constraint on `users.email`, so concurrent updates can't
  create duplicates. A constraint violation is mapped to `409 Email already in use`, never `500`.
- An `Idempotency-Key` header sent with `PATCH` is ignored.

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
