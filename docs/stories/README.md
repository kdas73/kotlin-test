# Stories

A **story** is one unit of work: requirements, technical details, acceptance criteria, the acceptance tests written
for them, and the record of how it was implemented and reviewed. Stories live in this folder, one Markdown file per
story, and follow [TEMPLATE.md](TEMPLATE.md).

Everything in a story is written in **English**, even when the original requirements were given in another language.

We work **test-first (TDD)**:
1. Acceptance tests are written from the acceptance criteria before any production code.
2. They are reviewed by a separate role.
3. They are then **locked**.
4. The implementer makes the locked tests pass and cannot change them.

## Roles and skills

| Role          | Skill (Claude Code)                                             | Responsibility                                                    |
|---------------|-----------------------------------------------------------------|-------------------------------------------------------------------|
| Story writer  | [`/story-writer`](../../.claude/skills/story-writer/SKILL.md)   | Analyses requirements and creates `Pending` stories               |
| Test writer   | [`/test-writer`](../../.claude/skills/test-writer/SKILL.md)     | Writes failing black-box acceptance tests for every AC            |
| Test reviewer | [`/test-reviewer`](../../.claude/skills/test-reviewer/SKILL.md) | Verifies the acceptance tests and locks them                      |
| Implementer   | [`/implementer`](../../.claude/skills/implementer/SKILL.md)     | Implements the story until the locked tests pass; records evidence |
| Reviewer      | [`/reviewer`](../../.claude/skills/reviewer/SKILL.md)           | Verifies the implementation against requirements and ACs          |

Each skill is run manually, in its own fresh context. **The roles must not share a context**: in particular, the
implementer must never be the agent that wrote or approved the acceptance tests. Agents other than Claude follow the same
procedures by reading the `SKILL.md` files.

## File naming

`STORY-NNN-kebab-case-slug.md`, e.g. `STORY-001-create-books-api.md`.

- `NNN` is a 3-digit sequential ID: the highest existing ID + 1. IDs are never reused or renumbered.
- The `id` in the front matter must match the file name.
- `TEMPLATE.md` and `README.md` are not stories.

## Status lifecycle

```
Pending ─► Test Writing ─► Test Review ─► Ready ─► In Progress ─► In Review ─► Completed (immutable)
                ▲               │                    │   ▲            │
                └───────────────┘                    │   └────────────┘
               test changes requested                │   changes requested
                ▲                                    │
                └────────────────────────────────────┘
                 test defect (only with the user's approval)
```

The status is stored in the front matter as exactly one of:
`Pending`, `Test Writing`, `Test Review`, `Ready`, `In Progress`, `In Review`, `Completed`.

| From → To                    | Who           | Condition |
|------------------------------|---------------|-----------|
| Pending → Test Writing       | test-writer   | No open questions; every story in `depends_on` is `Completed`; **no other story is active** (WIP limit) |
| Test Writing → Test Review   | test-writer   | `Test design N` written; tests compile; every AC has a test; the new tests fail for the expected reason; all other tests pass |
| Test Review → Ready          | test-reviewer | Verdict **Approved**; the **Locked tests** table (path + SHA-256) is recorded in the test review |
| Test Review → Test Writing   | test-reviewer | Verdict **Changes requested**, with numbered findings |
| Ready → In Progress          | implementer   | — |
| In Progress → In Review      | implementer   | `Attempt N` evidence written; `./gradlew build` green (locked tests pass); `scripts/locked-tests.py verify` passes |
| In Review → Completed        | reviewer      | Verdict **Approved**: all requirements and ACs met, build green, locked tests verified, no blocking findings |
| In Review → In Progress      | reviewer      | Verdict **Changes requested**, with numbered findings |
| In Progress → Test Writing   | implementer   | **Only after the user explicitly approves** it, because of a recorded *Test defect* (see below) |

Any other transition is forbidden, including moving backwards from `Completed`, skipping a review, or the implementer
starting a story that is not `Ready`.

On every transition, change all of these together:
1. `status` in the front matter,
2. `updated` in the front matter (ISO date),
3. a new row appended to the **Status history** table (`Date | From | To | By | Note`).

### WIP limit: one active story

A story is **active** from `Test Writing` through `In Review`. **At most one story may be active at a time.**
A story's acceptance tests are red until it is implemented, so a second active story would break the build for the other one.
Any number of stories may wait in `Pending`.

## Section ownership

| Section                                                                          | Owner                  | Editable when                                     |
|----------------------------------------------------------------------------------|------------------------|---------------------------------------------------|
| Context, Requirements, Out of scope, Technical details, Acceptance criteria, Open questions | story writer / user | Only while `Pending`. Frozen afterwards.  |
| Acceptance tests                                                                 | test-writer            | Append-only: one new `### Test design N` per round |
| Test review                                                                      | test-reviewer          | Append-only: one new `### Test review N` per review |
| Execution evidence                                                               | implementer            | Append-only: one new `### Attempt N` per attempt  |
| Review                                                                           | reviewer               | Append-only: one new `### Review N` per review    |
| Front matter `status` / `updated`, Status history                                | whoever makes the transition | On transitions only (history is append-only) |

- Nobody except the user edits requirements or acceptance criteria after `Pending`. If one is wrong, contradictory or
  untestable, record it in your own section (test design, test review, or attempt) and ask the user. Only the user may
  change a frozen requirement, and the change is recorded as a Status history note.
- Earlier entries are never rewritten. A new entry answers the findings of the latest review.

## TDD and locked tests

### Acceptance tests are black-box

The test-writer's acceptance tests check the system **from the outside only**:
- **HTTP API:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `RestTestClient`, raw JSON bodies, and assertions on
  status codes, headers, JSON fields and `ProblemDetail` responses.
- **Database:** `JdbcTemplate` with plain SQL, for test data setup and for checking persisted state, using the tables
  and columns from the story's *Technical details*.
- **No imports from production code** (`com.example.kotlintest.*`). The tests compile before any implementation exists,
  fail for the right reason (for example 404 or a missing table), and don't dictate the internal design.
- Location and naming: `src/test/kotlin/com/example/kotlintest/acceptance/Story<NNN><Name>AcceptanceTest.kt`. Test names
  start with the AC ID, e.g. ``fun `AC-1 creates a book and returns 201 with Location`()``.

The implementer may add **its own** tests (unit, slice, extra integration tests) in other files. Those are not locked.

### Locking

- When the test-reviewer approves, they run `python3 scripts/locked-tests.py hash <files>` and paste the resulting
  **Locked tests** table (repo-relative path + SHA-256) into the `### Test review N` entry. From then on, those files
  are **locked**.
- `python3 scripts/locked-tests.py verify STORY-NNN` recomputes the hashes from the story's last *Approved* test review.
  It reports `OK`, `MODIFIED` or `MISSING` and exits non-zero on any change. The implementer runs it before hand-over, and
  the reviewer runs it again.
- Enforcement: the `PreToolUse` hook [.claude/hooks/protect-locked-tests.py](../../.claude/hooks/protect-locked-tests.py)
  blocks `Edit`/`Write` on any locked file (from any story, including Completed ones) while a story is `Ready`,
  `In Progress` or `In Review`.
- The implementer must not modify, delete, rename, skip (`@Disabled`, assumptions, tags) or weaken locked tests. They also
  must not change the test infrastructure the locked tests rely on in a way that weakens them: Gradle test filters,
  `src/test/resources`, or test profiles.
- Locked tests of a Completed story may be changed later only by a **new story's** test-writer, if that story changes
  the behaviour. Such tests are then locked again by the new story.

### Test defects

If the implementer believes a locked test is wrong (it contradicts the requirements or is impossible to satisfy), they:
1. don't touch the test,
2. record a **Test defect** under *Deviations and notes* in the current attempt: which test, why, and which requirement it conflicts with,
3. keep the story `In Progress` and ask the user.

Only if the user agrees does the implementer move the story `In Progress → Test Writing`. The test-writer then fixes the
tests in a new `Test design N`, and the test-reviewer re-approves and re-locks them.

## Immutability

- Once a story is **`Completed`**, its file is **never edited again**, not even to fix typos.
- The reviewer's switch to `Completed` is the **last** edit of the file. The review entry, the Status history row and
  `updated` are written first; changing `status:` in the front matter to `Completed` comes last.
- Follow-up work, bugs found later, or scope changes go into a **new story** that references the old one in
  `related: [STORY-NNN]`.
- Enforcement: a Claude Code `PreToolUse` hook ([.claude/hooks/protect-completed-stories.py](../../.claude/hooks/protect-completed-stories.py))
  blocks `Edit`/`Write` on Completed stories. Editing story or test files through shell commands (`sed`, `>`,
  `Set-Content`, ...) is forbidden, because hooks cannot see those.

## Writing good stories

- **Small and independent:** one story can be implemented and reviewed in a single session. Split larger features and
  use `depends_on` for ordering.
- **Requirements** are numbered `R-1`, `R-2`, ... and describe *what*, not *how*.
- **Acceptance criteria** are numbered `AC-1`, `AC-2`, ... and use Given / When / Then. Each AC must be verifiable
  **black-box**: through the HTTP API and/or by checking database state with SQL. Avoid vague words ("fast", "user-friendly").
- **Technical details** are concrete: endpoints (method, path, request/response JSON, status codes), tables and columns
  for the Flyway migration, affected packages, constraints. The test-writer relies on them, so endpoints, JSON field names
  and table/column names must be exact. They must follow [AGENTS.md](../../AGENTS.md).
- **Out of scope** lists what is deliberately not included, so the reviewers can detect scope creep.
- **Open questions** must be empty (resolved with the user) before the story leaves `Pending`.
