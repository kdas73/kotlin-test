---
id: STORY-008
title: Limit the request body size of the API
status: Pending
created: 2026-11-09
updated: 2026-11-09
depends_on: [STORY-001]
related: [STORY-001]
---

# STORY-008: Limit the request body size of the API

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-09 | —    | Pending | story-writer | Story created from STORY-001 Review 1, finding F-1 |

## Context

STORY-001 Review 1 (finding **F-1**, minor) found that `common/idempotency/CachedBodyFilter.kt` reads the body of
every `POST /api/v1/**` request into memory with `readAllBytes()`, without any size limit, and before the
`Idempotency-Key` header is checked. Any client can make the server buffer arbitrarily large bodies. Other methods with
a body (the `PATCH` endpoints of STORY-002 and STORY-003) would be read by Jackson without a limit as well.

The real request bodies of the shop are small JSON documents (users, goods, deposits, orders). This story sets one
project-wide limit for every request body under `/api/v1/`: **65536 bytes (64 KiB)**. Larger bodies are rejected with
`413 Content Too Large` (RFC 9110) before anything else happens.

## Requirements

- **R-1:** A request to a path under `/api/v1/` whose body is larger than 65536 bytes is rejected with `413` and a
  `ProblemDetail` with title `Request body too large`. It has no side effects. A body of exactly 65536 bytes is allowed.
- **R-2:** The limit applies to the bytes actually sent, both when the request declares a `Content-Length` and when the
  body is sent with chunked transfer encoding (no `Content-Length`).
- **R-3:** The limit applies to every HTTP method and is checked before the request is dispatched to a controller: before
  the `Idempotency-Key` checks, handler mapping and validation. A rejected `POST` leaves no `idempotency_keys` record, so
  its key can be used again.

## Out of scope

- A configurable limit (property) or different limits per endpoint.
- Multipart requests and file uploads.
- Paths outside `/api/v1/` (for example `/actuator/**`).
- Limits on headers, query strings or the number of JSON elements.

## Technical details

### API

No new endpoints. Every existing and future endpoint under `/api/v1/` gets one more error response:

| Status | Title                    | When                                         |
|--------|--------------------------|----------------------------------------------|
| `413`  | `Request body too large` | the request body is larger than 65536 bytes |

The error is an RFC 9457 `ProblemDetail` (`Content-Type: application/problem+json`) with at least `status` (`413`) and
`title`, like every other error. No stack traces.

**Rules:**
- Limit: **65536 bytes** of the raw request body (not characters), defined once as a constant.
- If `Content-Length` is present and greater than 65536, the request is rejected without reading the body.
- Otherwise (no `Content-Length`, chunked), the body is read only up to 65537 bytes; if there are more than 65536 bytes,
  the request is rejected.
- The check runs in a servlet filter for every method under `/api/v1/`, before `CachedBodyFilter` buffers the body and
  before the `DispatcherServlet`. Therefore `413` wins over every other outcome, including `400 Idempotency-Key header
  required`, `404` and `405`.

### Data model

No migration.

### Affected code

- `common/`: a new filter that enforces the limit (or `CachedBodyFilter` extended), ordered before `CachedBodyFilter`.
  For `POST` the bounded read can be the read that `CachedBodyFilter` already does. For other methods, either buffer
  the body with the same bounded read or wrap the input stream so that it fails after 65536 bytes; the implementer
  chooses, as long as every method gets `413`.
- Filters run outside the `@RestControllerAdvice`. The filter must write the `application/problem+json` response itself
  (or hand the exception to the `HandlerExceptionResolver`), with the same shape as other problem responses (`type`,
  `title`, `status`, `detail`, `instance`).

### Constraints and notes

- Update `AGENTS.md`: the body size limit in the REST API conventions, and `413 Request body too large` in the error
  titles table.
- Tomcat swallows up to `server.tomcat.max-swallow-size` (default 2 MB) of an unread body before it closes the
  connection. Bodies used in tests must stay below 2 MB so the client reliably reads the `413` response.
- STORY-001's locked acceptance tests must keep passing (their bodies are far below the limit).

## Acceptance criteria

Tests share one database, so every test uses its own unique emails and idempotency keys. "Padding" means JSON
whitespace (spaces) appended after the closing `}` of a valid JSON object, which is still valid JSON. Body sizes are
counted in bytes of the UTF-8 encoded body.

- **AC-1:** Given a new key, when the client sends `POST /api/v1/users` with `Content-Type: application/json` and a body
  of exactly 65536 bytes (`{"name":"Max","email":"max-<unique>@example.com"}` plus padding), then the response is `201`
  and the `users` table has exactly one row with that email.
- **AC-2:** Given a new key `K`, when the client sends `POST /api/v1/users` with a declared `Content-Length` and a body
  of 65537 bytes (a valid user with email `big-<unique>@example.com` plus padding), then the response is `413` with
  `Content-Type: application/problem+json` and title `Request body too large`, no user with that email exists, and the
  `idempotency_keys` table has no row with `idempotency_key = K`.
- **AC-3:** Given a new key `K`, when the client sends `POST /api/v1/users` with a body of 100000 bytes (a valid user
  with email `chunked-<unique>@example.com` plus padding) using chunked transfer encoding without a `Content-Length`
  header, then the response is `413` with title `Request body too large`, no user with that email exists, and
  `idempotency_keys` has no row with `idempotency_key = K`.
- **AC-4:** Given a `POST /api/v1/users` with key `K` was rejected with `413` as in AC-2, when the client sends a normal
  valid `POST /api/v1/users` with key `K` and a new email, then the response is `201` without an
  `Idempotent-Replayed` header and the user exists.
- **AC-5:** When the client sends `POST /api/v1/users` **without** an `Idempotency-Key` header and with a body of 65537
  bytes, then the response is `413` with title `Request body too large` (not `400 Idempotency-Key header required`).
- **AC-6:** Given an existing user, when the client sends `PATCH /api/v1/users/{id}` with `Content-Type:
  application/json` and a body of 65537 bytes (`{"name":"Changed"}` plus padding), then the response is `413` with title
  `Request body too large`, and the user's `users` row (`name`, `email`) is unchanged.

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
