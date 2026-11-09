---
id: STORY-009
title: Count text length limits in Unicode code points
status: Completed
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
| 2026-11-09 | Pending | Test Writing | test-writer | Writing acceptance tests |
| 2026-11-09 | Test Writing | Test Review | test-writer | Test design 1: 5 acceptance tests, red run recorded |
| 2026-11-09 | Test Review | Ready | test-reviewer | Tests locked |
| 2026-11-09 | Ready | In Progress | implementer | Implementation started |
| 2026-11-09 | In Progress | In Review | implementer | Attempt 1: build green, locked tests verified |
| 2026-11-09 | In Review | Completed | reviewer | Review 1: Approved (minor findings F-1, F-2) |

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

### Test design 1 — 2026-11-09

**Files:**
- `src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt`

**AC coverage:**

| AC   | Test (class › method)                                                                                                   |
|------|-------------------------------------------------------------------------------------------------------------------------|
| AC-1 | `Story009CodePointLengthAcceptanceTest` › `AC-1 accepts a name of 100 emoji and stores 100 code points`                  |
| AC-2 | `Story009CodePointLengthAcceptanceTest` › `AC-2 rejects a name of 101 emoji with 400 and no side effects`                |
| AC-3 | `Story009CodePointLengthAcceptanceTest` › `AC-3 accepts a name of 100 code points with 101 UTF-16 units and a name of a single emoji` |
| AC-4 | `Story009CodePointLengthAcceptanceTest` › `AC-4 accepts an email of exactly 254 code points`                             |
| AC-5 | `Story009CodePointLengthAcceptanceTest` › `AC-5 rejects an email of 255 code points with 400`                            |

**Red run:** `python3 scripts/app.py test` (`./gradlew test`): compiles; 53 tests, 3 failed. All 48 pre-existing tests
pass (including the 26 STORY-001 acceptance tests). 3 of the 5 new tests fail; AC-2 and AC-5 pass (see notes).

| Test | Result before implementation |
|------|------------------------------|
| `AC-1 accepts a name of 100 emoji ...` | fails: `400` (`"detail":"Invalid request content."`) instead of `201`; `@Size(max = 100)` counts 200 UTF-16 units |
| `AC-2 rejects a name of 101 emoji ...` | passes: 101 emoji are already rejected (202 UTF-16 units); boundary guard |
| `AC-3 accepts a name of 100 code points ...` | fails at case (a): `400` instead of `201`; `"a" × 99 + 😀` has 101 UTF-16 units |
| `AC-4 accepts an email of exactly 254 code points` | fails: `400` instead of `201`; the email has 459 UTF-16 units, over `@Size(max = 254)` |
| `AC-5 rejects an email of 255 code points ...` | passes: already rejected (461 UTF-16 units); boundary guard |

**Notes:**
- **AC-2 and AC-5 are green before implementation, by construction.** They pin the upper limits (101 and 255 code
  points must be rejected). Any input that exceeds a code-point limit also exceeds the same limit in UTF-16 units, so no
  black-box test of these two ACs can be red today. They guard against an implementation that removes or loosens the
  limit, and they fail if it does.
- AC-3 (a) and (b) are one test, because the AC states "each response is `201`". Case (b) (a single emoji, 2 UTF-16
  units) is accepted today, so it guards the lower bound `min = 1` in code points. Case (a) makes the test red.
- Encoding: `😀` is built with `String(Character.toChars(0x1F600))`, so the source file encoding doesn't matter. Bodies are
  sent as UTF-8 bytes (`Content-Type: application/json`), with the characters written literally, not as JSON `\u` escapes.
  Each test asserts its own lengths with `codePointCount` (and the UTF-16 length where the AC relies on it). The `400` detail
  `Invalid request content.` shows that the JSON was parsed and the rejection comes from bean validation, not from a
  decoding error.
- AC-4/AC-5 email: `k × 😀 + "@" + <UUID> + ".example.com"`, where the suffix has 49 code points, so k = 254 − 49 = 205
  (asserted > 127). The suffix is lower-case ASCII, so lower-casing leaves the email unchanged. The response `email` and
  `SELECT length(email) FROM users WHERE email = ?` (SQLite `length()` counts characters) are checked against the email as sent.
- Isolation: no table cleanup (shared DB, like STORY-001); every test uses UUID emails and UUID idempotency keys and
  asserts only on its own rows.

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

| AC   | Covered | Assertion matches Given/When/Then | Note |
|------|---------|-----------------------------------|------|
| AC-1 | yes     | yes | New UUID key; 100 × U+1F600 (asserted 100 code points); asserts `201`, response `name`, and `SELECT name, length(name)` = the string and `100`. Red: `400` before implementation. |
| AC-2 | yes     | yes | Asserts `400`, `application/problem+json` (compatible check) and body `status`, no `users` row for the email, no `idempotency_keys` row for the key. Green before implementation by construction (see note below). |
| AC-3 | yes     | yes | Case (a) asserts 100 code points and 101 UTF-16 units of the input; case (b) asserts 1 code point; both get their own key and email; both `201` with the `name` returned unchanged. Red at case (a). |
| AC-4 | yes     | yes | `k × 😀 + "@" + <uuid> + ".example.com"`, k = 205 (asserted > 127), 254 code points and > 254 UTF-16 units asserted; `201`, response `email` equals the sent email, `SELECT length(email)` = `254`. Red: `400`. |
| AC-5 | yes     | yes | Same construction with k + 1 (asserted 255 code points); `400` problem+json, no `users` row. Green before implementation by construction. |

AC-2 and AC-5 cannot be red before implementation: any input over a code-point limit is also over the same limit in
UTF-16 units. They are boundary guards that fail if the implementation drops or loosens the limit. Accepted.

**Quality checks:** black-box only (no production imports; only `RestTestClient` and `JdbcTemplate`): yes ·
deterministic (no sleeps, clock or order dependence; UUID data): yes · isolated test data (unique emails and keys, asserts
only on own rows): yes · AC-prefixed names, English: yes · no `@Disabled` / tags / assumptions: yes · no changes to
`build.gradle.kts` or test resources (`git status`: only the story file and the new test file): yes · no
over-specification (no internal design dictated; the `400` is checked by status and content type only): yes ·
encoding-safe (emoji built with `Character.toChars(0x1F600)`, body sent as UTF-8 bytes): yes ·
red for the expected reason (own run): yes.

**Red run (own run):** `python3 scripts/app.py test`: compiles; 53 tests, 3 failures, 0 errors, 0 skipped. Failing:
`AC-1`, `AC-3` (case a), `AC-4`, each `expected: <201> but was: <400>` with body
`{"detail":"Invalid request content.",...,"status":400,"title":"Bad Request"}`, i.e. rejected by bean validation
(`@Size` counts UTF-16 units), not by JSON decoding. `AC-2` and `AC-5` pass. All 48 other tests pass, including the 26
`Story001RegisterUsersAcceptanceTest` tests.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| T-1 | minor    | Story ACs (not the test file) | U+1F600 is one code point and also one grapheme cluster, so the ACs (and therefore the tests) don't tell code-point counting apart from grapheme-cluster counting, which *Out of scope* excludes. An implementation counting grapheme clusters would pass. The tests match the ACs exactly, so no test change is requested; the implementer must follow R-1/R-2 and *Technical details* (`String.codePointCount`). If the user wants this pinned, it needs a new AC (for example a name of 50 × `👍🏽`, 100 code points, accepted, and 51 × `👍🏽` rejected). |

**Locked tests:**

| File | SHA-256 |
|------|---------|
| `src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt` | 7bb24605c932075128637079b899c0536a7b78947560619c97cdc8463ae18184 |

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

**Summary:** Added a reusable Jakarta Bean Validation constraint `@CodePointLength(min, max)` in `common/validation/`.
Its validator counts `String.codePointCount(0, length)` and leaves `null` to other constraints. `CreateUserRequest.name`
now uses `@CodePointLength(min = 1, max = 100)` and `email` uses `@CodePointLength(max = 254)` instead of `@Size`.
Validation still runs on the trimmed values (trimming happens in the `@JsonCreator`) and before the service lower-cases
the email, so the order of checks is unchanged. Error responses are unchanged (`400` `ProblemDetail` from the existing
`MethodArgumentNotValidException` handling). `AGENTS.md` now states the code-point rule.

**Changed files:**
- `src/main/kotlin/com/example/kotlintest/common/validation/CodePointLength.kt` (new): the `@CodePointLength` annotation
  and `CodePointLengthValidator` (rejects negative `min` and `max < min` at initialization).
- `src/main/kotlin/com/example/kotlintest/user/UserDtos.kt`: `name` and `email` use `@CodePointLength` instead of `@Size`.
- `src/test/kotlin/com/example/kotlintest/common/validation/CodePointLengthTest.kt` (new): unit tests for the validator.
- `AGENTS.md`: Kotlin & Spring conventions gain the rule "Text length limits count Unicode code points. Use the
  code-point constraint from `common/validation/` instead of `@Size` on `String` fields."; the project layout lists
  `common/validation/`.

**Migrations:** none.

**Acceptance criteria evidence:**

| AC   | Locked acceptance test (now passing) | Additional tests by the implementer |
|------|--------------------------------------|-------------------------------------|
| AC-1 | `Story009CodePointLengthAcceptanceTest` › `AC-1 accepts a name of 100 emoji and stores 100 code points` | `CodePointLengthTest` › `counts a supplementary character as one code point` |
| AC-2 | `Story009CodePointLengthAcceptanceTest` › `AC-2 rejects a name of 101 emoji with 400 and no side effects` | `CodePointLengthTest` › `counts a supplementary character as one code point` |
| AC-3 | `Story009CodePointLengthAcceptanceTest` › `AC-3 accepts a name of 100 code points with 101 UTF-16 units and a name of a single emoji` | `CodePointLengthTest` › `counts mixed BMP and supplementary characters in code points`, `enforces the minimum length` |
| AC-4 | `Story009CodePointLengthAcceptanceTest` › `AC-4 accepts an email of exactly 254 code points` | — |
| AC-5 | `Story009CodePointLengthAcceptanceTest` › `AC-5 rejects an email of 255 code points with 400` | — |

Other own tests: `CodePointLengthTest` › `counts each code point of a grapheme cluster separately` (👍🏽 = 2 code points;
addresses Test review 1 T-1 at unit level: code points, not grapheme clusters, are counted), `leaves null to other
constraints`, `reports the limits in the message`.

**Build:** `python3 scripts/app.py build` (`./gradlew build`): BUILD SUCCESSFUL, 59 tests, 0 failures.

```
BUILD SUCCESSFUL in 12s
=== Test summary ===
  com.example.kotlintest.acceptance.Story001RegisterUsersAcceptanceTest: 26 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.acceptance.Story009CodePointLengthAcceptanceTest: 5 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.idempotency.IdempotencyKeyTest: 4 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.money.MoneyTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.common.validation.CodePointLengthTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.config.SqliteDialectTest: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.config.VirtualThreadsTest: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.KotlinTestApplicationTests: 1 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserApiTest: 6 tests, 0 failures, 0 errors, 0 skipped
  com.example.kotlintest.user.UserPersistenceTest: 3 tests, 0 failures, 0 errors, 0 skipped
  TOTAL: 59 tests, 0 failures, 0 errors, 0 skipped

build: SUCCESSFUL
```

**Locked tests verify:** `python3 scripts/locked-tests.py verify STORY-009`

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt
STORY-009-count-text-length-in-code-points: 1/1 locked tests unchanged
```

**Deviations and notes:**
- A clean build (`./gradlew clean build`) prints one Kotlin compiler warning, in the **locked** test file:
  `Story009CodePointLengthAcceptanceTest.kt:146:35 Unnecessary safe call on a non-null receiver of type 'ByteArray'.`
  It comes from the test design, not from production code; the implementer may not change the file. Production code
  and the implementer's own tests compile without warnings. The test-writer of a later story may remove it.
- Test review 1 T-1 (code points vs. grapheme clusters not distinguished by the ACs): the validator follows R-1/R-2 and
  *Technical details* (`String.codePointCount`); the unit test with 👍🏽 pins it.
- No test defects.

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
| R-1 | yes | `CreateUserRequest.name` (`user/UserDtos.kt:15-17`): `@NotBlank` + `@CodePointLength(min = 1, max = 100)`. `CodePointLengthValidator.isValid` (`common/validation/CodePointLength.kt:40-44`) counts `value.codePointCount(0, value.length)`, i.e. code points, not UTF-16 units and not grapheme clusters. The value is trimmed in the `@JsonCreator` (`UserDtos.kt:29-30`) before validation. |
| R-2 | yes | `CreateUserRequest.email` (`UserDtos.kt:19-22`): `@CodePointLength(max = 254)` replaces `@Size(max = 254)`; `@NotBlank` and `@Pattern` unchanged. Validation runs on the trimmed value before the service lower-cases it (unchanged flow). |

**Acceptance criteria:**

| AC   | Result | How checked |
|------|--------|-------------|
| AC-1 | pass   | Locked test `AC-1 accepts a name of 100 emoji and stores 100 code points` passed in my run. The implementation is a generic code-point count, not a special case. |
| AC-2 | pass   | Locked test `AC-2 rejects a name of 101 emoji ...` passed: `400` problem+json, no `users` row, no `idempotency_keys` row. Rejection comes from `MethodArgumentNotValidException`, which is thrown inside the idempotent flow before anything is stored. |
| AC-3 | pass   | Locked test `AC-3 accepts a name of 100 code points with 101 UTF-16 units and a name of a single emoji` passed. `CodePointLengthTest` › `counts mixed BMP and supplementary characters in code points` and `enforces the minimum length` cover the same boundaries at unit level. |
| AC-4 | pass   | Locked test `AC-4 accepts an email of exactly 254 code points` passed (k = 205, 459 UTF-16 units, SQLite `length(email)` = 254). |
| AC-5 | pass   | Locked test `AC-5 rejects an email of 255 code points with 400` passed. |

Test review 1 T-1 (code points vs. grapheme clusters): the validator uses `String.codePointCount`, as required by
*Technical details*, and `CodePointLengthTest` › `counts each code point of a grapheme cluster separately` (👍🏽 = 2
code points) pins it. Resolved at unit level.

**Build:** `./gradlew clean build`: BUILD SUCCESSFUL. `python3 scripts/app.py test --rerun`: 59 tests, 0 failures,
0 errors, 0 skipped (`Story009CodePointLengthAcceptanceTest` 5/5, `Story001RegisterUsersAcceptanceTest` 26/26,
`CodePointLengthTest` 6/6, guard tests `KotlinTestApplicationTests`, `SqliteDialectTest`, `VirtualThreadsTest` pass).
One compiler warning, in the locked test file (see F-2). Production code and the implementer's tests compile without
warnings.

**Locked tests verify:**

```
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt
STORY-009-count-text-length-in-code-points: 1/1 locked tests unchanged
OK       src/test/kotlin/com/example/kotlintest/acceptance/Story001RegisterUsersAcceptanceTest.kt
STORY-001-register-users-with-account-and-idempotent-post: 1/1 locked tests unchanged
```

No `@Disabled`, assumptions or tags in `src/`; no changes to `build.gradle.kts`, `settings.gradle.kts` or
`src/test/resources` (`git diff HEAD` is empty for them). No `@Size` remains on any `String` field in `src/main`.

**Conventions and scope:** no migration (none needed); no `!!`; blocking code only; English everywhere; the constraint
is reusable (`common/validation/`), validates its own `min`/`max` at initialization and leaves `null` to `@NotBlank`.
`AGENTS.md` gains the required rule (Kotlin & Spring conventions) and lists `common/validation/` in the project layout.
Changed files match *Changed files* in Attempt 1; nothing from *Out of scope* (no normalization, no grapheme counting,
no change to the email pattern, trimming or lower-casing, no STORY-002/003 fields). Error responses are unchanged
(`400` `ProblemDetail`).

**Evidence accuracy:** the files, test names, test counts and verify output in Attempt 1 match what I observed, including
the reported compiler warning.

**Findings:**

| ID  | Severity | Location | Description |
|-----|----------|----------|-------------|
| F-1 | minor    | `docs/stories/STORY-009-count-text-length-in-code-points.md` (Attempt 1) | The Attempt 1 edit replaced the `## Review` heading instead of inserting the attempt above it, so the reviewer's template comment ended up under *Execution evidence*. I restored the `## Review` heading (structure only, no content changed) when recording this review. Expected: implementers append their attempt without removing other sections' headings. |
| F-2 | minor    | `src/test/kotlin/com/example/kotlintest/acceptance/Story009CodePointLengthAcceptanceTest.kt:146` | `responseBodyContent?.toString(...)`: Kotlin warns "Unnecessary safe call on a non-null receiver of type 'ByteArray'". AGENTS.md requires a build with no new warnings. The file is locked and was correctly left untouched by the implementer, who reported it. Expected: a later story's test-writer (or the user) removes the `?.` / `.orEmpty()` and re-locks the file. Not attributable to this implementation; does not block. |
