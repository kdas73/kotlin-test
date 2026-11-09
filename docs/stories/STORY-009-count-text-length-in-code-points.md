---
id: STORY-009
title: Count text length limits in Unicode code points
status: Pending
created: 2026-11-09
updated: 2026-11-09
depends_on: [STORY-001]
related: [STORY-001]
---

# STORY-009: Count text length limits in Unicode code points

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| 2026-11-09 | —    | Pending | story-writer | Story created from STORY-001 Review 1, finding F-2 |

## Context

STORY-001 R-2 says that a user's `name` must be 1–100 **characters** long and `email` at most 254 **characters**. STORY-001
Review 1 (finding **F-2**, minor) found that the implementation uses `@Size`, which counts UTF-16 code units. A character
outside the Basic Multilingual Plane (for example an emoji) takes two UTF-16 units, so a name of 51–100 such characters
is wrongly rejected with `400`.

The project decides that **every text length limit in the API counts Unicode code points**: not UTF-16 code units,
not bytes, not grapheme clusters. This story fixes user registration and provides a reusable constraint, so later
stories (STORY-002 user update, STORY-003 goods) count the same way.

## Requirements

- **R-1:** On registration (`POST /api/v1/users`), the length of the trimmed `name` (1–100) is counted in Unicode code
  points.
- **R-2:** On registration, the length of the trimmed `email` (at most 254) is counted in Unicode code points.

## Out of scope

- Unicode normalization (NFC/NFKC) of names or emails.
- Counting grapheme clusters (user-perceived characters, for example emoji with modifiers or ZWJ sequences count as
  several code points).
- Changing the email pattern, the trimming rules, the lower-casing, or any other validation rule of STORY-001.
- Text fields of other stories: STORY-002 and STORY-003 apply the rule themselves (they depend on this story).

## Technical details

### API

No new endpoints. `POST /api/v1/users` (STORY-001) keeps its request, response and error shapes. Only the meaning of
the length limits changes:

| Field   | Rule (after trimming)                           | Counted in            |
|---------|-------------------------------------------------|-----------------------|
| `name`  | not blank, 1–100                                | Unicode code points   |
| `email` | at most 254, matches the STORY-001 email pattern | Unicode code points   |

A violation is `400` with `Content-Type: application/problem+json`, as before.

### Data model

No migration. Note for the tests: SQLite's `length()` on a `TEXT` value returns the number of characters (Unicode code
points), not bytes.

### Affected code

- `common/validation/`: a reusable Jakarta Bean Validation constraint that limits a `String` by code points, for
  example `@CodePointLength(min = 1, max = 100)` with a validator based on `String.codePointCount(0, length)`. `null` is
  left to other constraints (`@NotBlank`).
- `user/UserDtos.kt`: `CreateUserRequest.name` and `email` use the new constraint instead of `@Size`.

### Constraints and notes

- The length is checked on the trimmed value as sent, before the email is lower-cased.
- Update `AGENTS.md` (Kotlin & Spring conventions): "Text length limits count Unicode code points. Use the
  code-point constraint from `common/validation/` instead of `@Size` on `String` fields."
- STORY-001's locked acceptance tests must keep passing (ASCII-only lengths are unchanged).

## Acceptance criteria

Tests share one database, so every test uses its own unique emails and idempotency keys. `😀` is U+1F600 (one code
point, two UTF-16 code units, four UTF-8 bytes). Request bodies are UTF-8 JSON with the characters sent literally.

- **AC-1:** Given a new key, when the client registers a user with `name` = 100 × `😀` and a unique email, then the
  response is `201`, the response `name` equals the 100 × `😀` string, and `SELECT name, length(name) FROM users WHERE
  email = ?` returns that string and `100`.
- **AC-2:** Given a new key `K`, when the client registers a user with `name` = 101 × `😀` and a unique email, then the
  response is `400` with `Content-Type: application/problem+json`, no user with that email exists, and `idempotency_keys`
  has no row with `idempotency_key = K`.
- **AC-3:** Given a new key for each case, when the client registers (a) `name` = 99 × `a` followed by one `😀`
  (100 code points, 101 UTF-16 units) and (b) `name` = a single `😀`, each with a unique email, then each response is
  `201` and returns that `name` unchanged.
- **AC-4:** Given a new key, when the client registers a user with `email` = k × `😀` + `@` + `<unique>` +
  `.example.com`, where `<unique>` is lower-case ASCII (for example a UUID) and k is chosen so that the email has exactly
  254 code points (so k > 127 and the email has more than 254 UTF-16 units), then the response is `201`, the response
  `email` equals the sent email, and `SELECT length(email) FROM users WHERE email = ?` returns `254`.
- **AC-5:** Given a new key, when the client registers a user with an email built like in AC-4 but with k + 1 emoji
  (exactly 255 code points), then the response is `400` with `Content-Type: application/problem+json` and no user with
  that email exists.

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
