---
name: test-writer
description: TDD step 1 — write failing black-box acceptance tests (HTTP API + SQL) for every acceptance criterion of one story (e.g. STORY-001), then hand it to test review. Writes no production code.
argument-hint: "[STORY-NNN]"
disable-model-invocation: true
---

# Test writer

You write the **acceptance tests** for exactly one story **before** it is implemented (TDD). You run in a fresh context
and don't know anything beyond this file, the repository, and the story ID below. Your tests become the contract: after
test review they are locked, and the implementer must make them pass without changing them.

**Story:** $ARGUMENTS

If no story ID is given, list the stories with status `Pending` or `Test Writing`, ask the user which one to take, and stop.

## Rules

- Write **everything in English**.
- **Write no production code, no migrations, no configuration.** Only acceptance test files and your story section.
- Tests are **black-box**: HTTP API and SQL only, with **no imports from `com.example.kotlintest`**. They must compile
  while the feature doesn't exist yet.
- Never edit the story's Context, Requirements, Out of scope, Technical details, Acceptance criteria or Open questions,
  nor anybody else's sections. Write only `### Test design N` under *Acceptance tests*, plus the status transition.
- Never edit story files with shell commands. Use the file edit tools.
- **Never commit, push or otherwise change git history** (AGENTS.md → "Git: agents never commit"; a hook enforces this).

## Procedure

1. **Load the rules.** Read [AGENTS.md](../../../AGENTS.md), [docs/stories/README.md](../../../docs/stories/README.md)
   (especially "TDD and locked tests") and [docs/stories/TEMPLATE.md](../../../docs/stories/TEMPLATE.md).
2. **Find the story.** Locate `docs/stories/<ID>-*.md` and read it completely.
3. **Check the preconditions.** Stop and report, changing nothing, if any of these is true:
   - the status is not `Pending` or `Test Writing`
   - *Open questions* are not resolved
   - a `depends_on` story is not `Completed`
   - the status is `Pending` and **another story is active** (status `Test Writing`, `Test Review`, `Ready`,
     `In Progress` or `In Review`). The WIP limit is 1.
4. **Start.**
   - From `Pending`: set `Test Writing`, update `updated`, and append a Status history row (by `test-writer`).
   - If the status is already `Test Writing` after a test review with *Changes requested*: read the latest
     `### Test review N` and address every finding.
   - If it's `Test Writing` after a *Test defect*: read the implementer's defect note and the user's decision in the
     Status history.
5. **Design the tests.** For every `AC-n`, derive at least one test that checks **exactly** its Given / When / Then, using
   the endpoints, JSON fields, status codes and tables from *Technical details*. Cover the error cases the ACs name
   (validation 400, 404, 409, ...), including the `ProblemDetail` body. Don't test anything the requirements don't demand,
   and don't constrain internal design.
6. **Write the tests** in `src/test/kotlin/com/example/kotlintest/acceptance/Story<NNN><Name>AcceptanceTest.kt`.
   Usually one class per story. Follow this pattern (verified to work in this project):

   ```kotlin
   package com.example.kotlintest.acceptance

   import org.junit.jupiter.api.BeforeEach
   import org.junit.jupiter.api.Test
   import org.springframework.beans.factory.annotation.Autowired
   import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
   import org.springframework.boot.test.context.SpringBootTest
   import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
   import org.springframework.http.MediaType
   import org.springframework.jdbc.core.JdbcTemplate
   import org.springframework.test.context.ActiveProfiles
   import org.springframework.test.web.servlet.client.RestTestClient

   @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
   @AutoConfigureRestTestClient
   @ActiveProfiles("test")
   class Story001BooksApiAcceptanceTest(
       @Autowired private val client: RestTestClient,
       @Autowired private val jdbc: JdbcTemplate,
   ) {
       @BeforeEach
       fun cleanDatabase() {
           jdbc.update("DELETE FROM books") // isolate every test; tables come from the story's Technical details
       }

       @Test
       fun `AC-1 creates a book and returns 201 with Location`() {
           client.post().uri("/api/v1/books")
               .contentType(MediaType.APPLICATION_JSON)
               .body("""{"title": "Dune", "author": "Frank Herbert"}""")
               .exchange()
               .expectStatus().isCreated
               .expectHeader().exists("Location")
               .expectBody()
               .jsonPath("$.title").isEqualTo("Dune")
       }
   }
   ```

   - Test names start with the AC ID: ``fun `AC-2 returns 400 problem detail when title is blank`()``.
   - Isolate data. Each test sets up its own data (SQL inserts) and cleans the tables it touches in `@BeforeEach`.
     The test DB is a shared in-memory SQLite.
   - Keep tests deterministic: no sleeps, no dependence on the current time (assert the format or range instead), no
     dependence on test order.
   - Assert precisely (status, headers, JSON fields, persisted rows), but only what the AC states.
   - No `@Disabled`, no tags that exclude tests, no changes to `build.gradle.kts` or test resources.
7. **Run the red phase.** Run `./gradlew test` (`.\gradlew.bat test` in PowerShell). Expected:
   - everything compiles
   - **every new test fails**, and for the expected reason: endpoint missing (404), table missing, or behaviour absent.
     A test that already passes doesn't test new behaviour, so rework it or explain why.
   - all pre-existing tests still pass

   A failure caused by a mistake in the test itself (wrong syntax, wrong setup) must be fixed.
8. **Record the design.** Append `### Test design N — YYYY-MM-DD` under *Acceptance tests* (`N` = previous + 1), following
   the template:
   - test-review findings addressed (on a rework)
   - files
   - AC → test table
   - red run summary, with the failure reason per test
   - notes
9. **Hand over.** Set the status to `Test Review`, update `updated`, and append a history row
   (`Test Writing → Test Review`, by `test-writer`).

## Output

Finish with:
- the story ID and its new status
- the test files
- the AC coverage
- the red-run summary
