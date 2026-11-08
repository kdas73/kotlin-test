---
id: STORY-NNN
title: Short imperative title
status: Pending        # Pending | In Progress | In Review | Completed
created: YYYY-MM-DD
updated: YYYY-MM-DD
depends_on: []         # stories that must be Completed first, e.g. [STORY-001]
related: []            # related or predecessor stories, e.g. [STORY-002]
---

# STORY-NNN: Short imperative title

## Status history

| Date       | From | To      | By           | Note          |
|------------|------|---------|--------------|---------------|
| YYYY-MM-DD | —    | Pending | story-writer | Story created |

## Context

Why this story exists: the problem, who needs it, and the expected outcome.

## Requirements

- **R-1:** ...
- **R-2:** ...

## Out of scope

- ...

## Technical details

### API

| Method | Path            | Request body | Success response | Error responses |
|--------|-----------------|--------------|------------------|-----------------|
| `GET`  | `/api/v1/...`   | —            | `200` + `...`    | `404`           |

Request/response shapes (JSON), validation rules, and error `ProblemDetail` types.

### Data model

Tables, columns and constraints for the Flyway migration (`V{n}__{description}.sql`, `STRICT` tables).

### Affected code

Packages and classes to add or change (package-by-feature, see AGENTS.md).

### Constraints and notes

Anything else the implementer must respect.

## Acceptance criteria

- **AC-1:** Given ..., when ..., then ...
- **AC-2:** Given ..., when ..., then ...

## Open questions

None.

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

| AC   | How verified                 | Evidence (test / command)              |
|------|------------------------------|----------------------------------------|
| AC-1 | automated test               | `BookControllerTest.returns 201 ...`   |

**Build:** `./gradlew build`: BUILD SUCCESSFUL, N tests, 0 failures (paste the relevant tail of the output).

**Deviations and notes:** none / description.
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

**Findings:**

| ID  | Severity | Location          | Description |
|-----|----------|-------------------|-------------|
| F-1 | blocking | `File.kt:42`      | ...         |

Severity: `blocking` (must be fixed before approval) or `minor` (should be fixed, does not block approval).
-->
