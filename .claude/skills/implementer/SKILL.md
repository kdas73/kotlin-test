---
name: implementer
description: TDD step 3 — implement one Ready story from docs/stories (e.g. STORY-001) until its locked acceptance tests pass, record Execution evidence, and move it to In Review. Also handles stories returned from review.
argument-hint: "[STORY-NNN]"
disable-model-invocation: true
---

# Implementer

You implement exactly **one** story whose acceptance tests were already written, reviewed and **locked** (TDD). Your goal
is to make the locked tests pass with production code that follows AGENTS.md. You run in a fresh context and don't know
anything beyond this file, the repository, and the story ID below.

**Story:** $ARGUMENTS

If no story ID is given, list the stories with status `Ready` or `In Progress`, ask the user which one to take, and stop.

## Rules

- Write **everything in English**: code, comments, messages, evidence.
- Implement **only** what the story requires. Respect *Out of scope*. Don't refactor unrelated code.
- **Locked acceptance tests are read-only for you.** These are the files in the *Locked tests* table of the latest
  approved `### Test review N`, plus locked tests of earlier stories.
  - Never modify, delete, rename, skip (`@Disabled`, assumptions, tags) or weaken them.
  - Never change test infrastructure they rely on: Gradle test filters, `src/test/resources`, test profiles.
  - A hook blocks edits to locked files, and `scripts/locked-tests.py verify` detects changes.
  - You **may** add your own tests (unit, slice, integration) in other files.
- **Never edit** the story's Context, Requirements, Out of scope, Technical details, Acceptance criteria, Open questions,
  *Acceptance tests* or *Test review* sections. Never edit or delete earlier *Execution evidence* attempts or any
  *Review* entries.
- Never set the status to `Completed`. Only the reviewer does that.
- Never edit story files with shell commands. Use the file edit tools.
- **Never commit, push or otherwise change git history.** Leave the changes uncommitted for the user (AGENTS.md → "Git:
  agents never commit"; a hook enforces this).
- Evidence must be **real**: paste actual command output and real test names. Never claim a check you didn't run.

## Procedure

1. **Load the rules.** Read [AGENTS.md](../../../AGENTS.md), [docs/stories/README.md](../../../docs/stories/README.md)
   (especially "TDD and locked tests") and [docs/stories/TEMPLATE.md](../../../docs/stories/TEMPLATE.md).
2. **Find the story.** Locate `docs/stories/<ID>-*.md` and read it completely, including the acceptance test designs and
   test reviews.
3. **Check preconditions.** The status must be `Ready`, or `In Progress` for a rework or a continuation. Otherwise stop
   and report to the user without changing anything.
4. **Start.**
   - From `Ready`: set `In Progress`, update `updated`, and append a Status history row (`Ready → In Progress`, by `implementer`).
   - If the status is already `In Progress` and the latest review says *Changes requested*, this is a rework. Read the
     latest `### Review N` and treat every finding as mandatory (minor ones too, unless the reviewer says otherwise). The
     status stays `In Progress`. Don't add a history row for this.
5. **Confirm the lock and the red state.**
   - Run `python3 scripts/locked-tests.py verify <ID>`. Every file must be `OK`.
   - Read the locked acceptance tests. They are your executable specification.
   - Run them (`./gradlew test --tests "com.example.kotlintest.acceptance.*"`) to see what fails.
6. **Plan.** Map every `R-n` and `AC-n` to the code you will write and to the locked test(s) that verify it. Look up the
   current Spring Boot / Kotlin / Java documentation linked in AGENTS.md for any API you're not sure about.
7. **Implement** following AGENTS.md:
   - package-by-feature
   - a Flyway migration for every schema change (table and column names exactly as in *Technical details*, because the
     locked tests use them)
   - DTOs separate from entities
   - validation
   - `ProblemDetail` errors
   - blocking code on virtual threads

   Work in small steps until the locked tests pass. Add your own tests where they help (domain logic, edge cases).
8. **Handle test defects.** If a locked test seems wrong (it contradicts the requirements or can't be satisfied
   correctly), **don't touch it and don't work around it.**
   1. Record a **Test defect** under *Deviations and notes* in your attempt: the test, why it is wrong, and the requirement it conflicts with.
   2. Keep the status `In Progress` and ask the user.
   3. Only if the user explicitly agrees, set the status to `Test Writing`, update `updated`, and append a history row
      (`In Progress → Test Writing`, by `implementer`, note: `Test defect approved by user: …`). Then stop.
9. **Build.** Run `./gradlew build` (`.\gradlew.bat build` in PowerShell). Fix failures until it is green with no new
   warnings and **all locked acceptance tests pass**.
10. **Verify the lock.** Run `python3 scripts/locked-tests.py verify <ID>`. Every file must be `OK`.
11. **Record evidence.** Append a new `### Attempt N — YYYY-MM-DD` under *Execution evidence* (`N` = previous attempt + 1),
    following the template:
    - summary
    - review findings addressed (`F-n: how fixed`) on a rework
    - changed files
    - migrations
    - AC → evidence table (locked test, plus your own tests if any)
    - build output tail with test counts
    - locked-tests verify output
    - deviations and notes
12. **Hand over.**
    - If the build is green, every locked test passes and verify is all `OK`: set the status to `In Review`, update
      `updated`, and append a history row (`In Progress → In Review`, by `implementer`).
    - Otherwise leave the story `In Progress`, describe the blocker under *Deviations and notes*, and tell the user.

## Output

Finish with:
- the story ID and its new status
- a short summary of the changes
- the AC → test results
- the build result
- the locked-tests verify result
