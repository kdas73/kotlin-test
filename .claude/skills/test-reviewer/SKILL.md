---
name: test-reviewer
description: TDD step 2 — review the acceptance tests of one story in Test Review (e.g. STORY-001) against its acceptance criteria; on approval lock them (SHA-256 table) and move the story to Ready, otherwise return it to Test Writing.
argument-hint: "[STORY-NNN]"
disable-model-invocation: true
---

# Test reviewer

You independently verify the **acceptance tests** of exactly one story before implementation starts. You run in a fresh
context and don't know anything beyond this file, the repository, and the story ID below. Once you approve, the tests are
**locked**: the implementer can't change them. So be strict. A weak or wrong test becomes a weak or wrong contract.

**Story:** $ARGUMENTS

If no story ID is given, list the stories with status `Test Review`, ask the user which one to review, and stop.

## Rules

- Write **everything in English**.
- **Don't modify** tests, production code or configuration. Your only writes to the story file are:
  - a new `### Test review N` entry under *Test review*
  - the status transition
- Never edit other sections or earlier entries. Never edit story files with shell commands. Use the file edit tools.
- **Never commit, push or otherwise change git history** (AGENTS.md → "Git: agents never commit"; a hook enforces this).
- Base every verdict on what **you** observed: test code you read, and test runs you executed.

## Procedure

1. **Load the rules.** Read [AGENTS.md](../../../AGENTS.md), [docs/stories/README.md](../../../docs/stories/README.md)
   (especially "TDD and locked tests") and [docs/stories/TEMPLATE.md](../../../docs/stories/TEMPLATE.md).
2. **Find the story.** Locate `docs/stories/<ID>-*.md` and read it completely: requirements, ACs, technical details, all
   test designs and test reviews.
3. **Check the precondition.** The status must be `Test Review`. Otherwise stop and report without changing anything.
4. **Read the tests.** Read every file listed in the latest `### Test design N`, and search
   `src/test/kotlin/**/acceptance/` for anything related that isn't listed.
5. **Verify.**
   - **Coverage:** every `AC-n` has at least one test, and each test really asserts its Given / When / Then: the right
     request, the right setup, and assertions on the stated outcome. Reject tests that would pass with a wrong
     implementation, for example only checking the status code when the AC requires a body or a persisted row.
   - **Requirements fit:** the tests match *Technical details* (paths, JSON fields, status codes, tables). They demand
     nothing beyond the requirements or *Out of scope*, and they don't dictate internal design.
   - **Black-box:** no imports from `com.example.kotlintest` (check the import lines). Only HTTP via `RestTestClient` and
     SQL via `JdbcTemplate`.
   - **Quality:**
     - deterministic: no sleeps, clock or order dependence
     - isolated: each test sets up and cleans its own data
     - readable, with AC-prefixed names
     - English
     - no `@Disabled` or excluding tags
     - no changes to `build.gradle.kts` or test resources
   - **Red phase:** run `./gradlew test` (`.\gradlew.bat test` in PowerShell) yourself. Everything compiles, every new
     test fails **for the expected reason** (missing endpoint, table or behaviour, not a bug in the test), and all other
     tests pass.
   - **Previous findings:** on a re-review, confirm that every finding from the latest test review was addressed.
6. **Decide.** Approve only if there are no `blocking` findings. Number findings `T-1, T-2, …`. Severity: `blocking` or `minor`.
7. **Record the review.** Append `### Test review N — YYYY-MM-DD — Verdict: <Approved | Changes requested>` under
   *Test review* (`N` = previous + 1), following the template:
   - AC coverage table
   - quality checks
   - findings
8. **Lock (Approved only).** Run
   `python3 scripts/locked-tests.py hash <every acceptance test file of this story>` from the repository root, and paste
   its output as the **Locked tests** table into your review entry, unchanged. Then run
   `python3 scripts/locked-tests.py verify <ID>` and make sure every file is `OK`.
9. **Transition.**
   - Approved: set the status to `Ready`, update `updated`, and append a history row (`Test Review → Ready`, by
     `test-reviewer`, note: `Tests locked`).
   - Changes requested: set the status to `Test Writing`, update `updated`, and append a history row
     (`Test Review → Test Writing`, by `test-reviewer`, note: `Changes requested: T-1, T-2`).

## Output

Finish with:
- the story ID
- the verdict and the new status
- the red-run result
- the locked files (if approved), or the findings
