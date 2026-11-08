# Stories

A **story** is one unit of work: requirements, technical details, acceptance criteria, and the record of how it was
implemented and reviewed. Stories live in this folder, one Markdown file per story, and follow [TEMPLATE.md](TEMPLATE.md).

Everything in a story is written in **English**, even when the original requirements were given in another language.

## Roles and skills

| Role         | Skill (Claude Code)                                          | Responsibility                                               |
|--------------|--------------------------------------------------------------|--------------------------------------------------------------|
| Story writer | [`/story-writer`](../../.claude/skills/story-writer/SKILL.md) | Analyses requirements and creates `Pending` stories          |
| Implementer  | [`/implementer`](../../.claude/skills/implementer/SKILL.md)   | Implements one story and records Execution evidence          |
| Reviewer     | [`/reviewer`](../../.claude/skills/reviewer/SKILL.md)         | Verifies the implementation against requirements and ACs     |

Each skill is run manually, in its own fresh context. Agents other than Claude follow the same procedures by reading the
`SKILL.md` files.

## File naming

`STORY-NNN-kebab-case-slug.md`, e.g. `STORY-001-create-books-api.md`.

- `NNN` is a 3-digit sequential ID: the highest existing ID + 1. IDs are never reused or renumbered.
- The `id` in the front matter must match the file name.
- `TEMPLATE.md` and `README.md` are not stories.

## Status lifecycle

```
Pending ──► In Progress ──► In Review ──► Completed (immutable)
                 ▲               │
                 └───────────────┘
                 changes requested
```

The status is stored in the front matter as exactly one of: `Pending`, `In Progress`, `In Review`, `Completed`.

| From → To                  | Who         | Condition                                                                    |
|----------------------------|-------------|------------------------------------------------------------------------------|
| Pending → In Progress      | implementer | No open questions; every story in `depends_on` is `Completed`               |
| In Progress → In Review    | implementer | `Attempt N` evidence written; every AC has evidence; `./gradlew build` green |
| In Review → Completed      | reviewer    | Verdict **Approved**: all requirements and ACs met, build green, no blocking findings |
| In Review → In Progress    | reviewer    | Verdict **Changes requested**, with numbered findings                        |

Any other transition is forbidden, including moving backwards from `Completed` or skipping `In Review`.

On every transition, change all of these together:
1. `status` in the front matter,
2. `updated` in the front matter (ISO date),
3. a new row appended to the **Status history** table (`Date | From | To | By | Note`).

## Section ownership

| Section                                                                          | Owner                  | Editable when                                     |
|----------------------------------------------------------------------------------|------------------------|---------------------------------------------------|
| Context, Requirements, Out of scope, Technical details, Acceptance criteria, Open questions | story writer / user | Only while `Pending`. Frozen afterwards.  |
| Execution evidence                                                               | implementer            | Append-only: one new `### Attempt N` per attempt  |
| Review                                                                           | reviewer               | Append-only: one new `### Review N` per review    |
| Front matter `status` / `updated`, Status history                                | whoever makes the transition | On transitions only (history is append-only) |

- The implementer **never** edits requirements or acceptance criteria. If one is wrong, contradictory or blocking, the
  implementer records it under *Deviations and notes* in the current attempt, leaves the story `In Progress`, and asks the user.
- Only the user may change a frozen requirement. The change is recorded as a Status history note.
- Earlier attempts and reviews are never rewritten. A new attempt answers the findings of the latest review.

## Immutability

- Once a story is **`Completed`**, its file is **never edited again**, not even to fix typos.
- The reviewer's switch to `Completed` is the **last** edit of the file. The review entry, the Status history row and
  `updated` are written first; changing `status:` in the front matter to `Completed` comes last.
- Follow-up work, bugs found later, or scope changes go into a **new story** that references the old one in
  `related: [STORY-NNN]`.
- Enforcement: a Claude Code `PreToolUse` hook ([.claude/hooks/protect-completed-stories.py](../../.claude/hooks/protect-completed-stories.py))
  blocks `Edit`/`Write` on Completed stories. Editing story files through shell commands (`sed`, `>`, `Set-Content`, ...)
  is forbidden, because the hook cannot see those.

## Writing good stories

- **Small and independent:** one story can be implemented and reviewed in a single session. Split larger features and
  use `depends_on` for ordering.
- **Requirements** are numbered `R-1`, `R-2`, ... and describe *what*, not *how*.
- **Acceptance criteria** are numbered `AC-1`, `AC-2`, ... and use Given / When / Then. Each AC must be verifiable by an
  automated test or a concrete command with an observable result. Avoid vague words ("fast", "user-friendly").
- **Technical details** are concrete: endpoints (method, path, request/response, status codes), tables and columns for
  the Flyway migration, affected packages, constraints. They must follow [AGENTS.md](../../AGENTS.md).
- **Out of scope** lists what is deliberately not included, so the reviewer can detect scope creep.
- **Open questions** must be empty (resolved with the user) before the story leaves `Pending`.
