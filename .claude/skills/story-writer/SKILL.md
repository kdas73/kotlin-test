---
name: story-writer
description: Analyse requirements and turn them into one or more Pending stories in docs/stories, following the story template and rules. Writes no code.
argument-hint: "[requirements text or path to a requirements file]"
disable-model-invocation: true
---

# Story writer

You turn requirements into well-formed **stories** in `docs/stories/`. You run in a fresh context and don't know anything
beyond this file, the repository, and the input below.

**Input requirements:** $ARGUMENTS

If the input is empty, ask the user for the requirements and stop.
If it is a path to a file, read that file.

## Rules

- Write **everything in English**. If the requirements are in another language, translate them faithfully. Don't add
  meaning that isn't there.
- **Write no production or test code.** Your only output is story files.
- Never edit an existing story that is not `Pending`. Never edit a `Completed` story at all (it's immutable, and a hook
  blocks it). If the requirements change completed work, create a new story with `related: [STORY-NNN]`.
- Never edit files with shell commands. Use the file edit tools.
- **Never commit, push or otherwise change git history** (AGENTS.md → "Git: agents never commit"; a hook enforces this).

## Procedure

1. **Load the rules.** Read [AGENTS.md](../../../AGENTS.md), [docs/stories/README.md](../../../docs/stories/README.md) and
   [docs/stories/TEMPLATE.md](../../../docs/stories/TEMPLATE.md). Follow them exactly.
2. **Understand the current system.** List the existing stories in `docs/stories/` (front matter `id`, `title`, `status`)
   to avoid duplicates and find dependencies. Inspect the relevant code, the Flyway migrations
   (`src/main/resources/db/migration`) and the API, so the Technical details match reality.
3. **Analyse the requirements.** Identify the actors, operations, data, validation rules, error cases and edge cases.
   For each ambiguity, contradiction or missing decision (field types, uniqueness, pagination, error behaviour, ...),
   **ask the user** with concrete options and a recommendation. Don't guess silently. If an answer can't be had now,
   record it under *Open questions*. Such a story can't be started until it's resolved.
4. **Split into stories.** Each story must be small enough to implement and review in one session and must deliver a
   verifiable result. Typical split: one story per resource or use case. Use `depends_on` for ordering. Don't create
   stories that only make sense together with another unfinished story, unless they are linked by `depends_on`.
5. **Write each story** from the template:
   - File `docs/stories/STORY-NNN-kebab-slug.md`. `NNN` is the highest existing ID + 1, 3 digits, and increments for each new story.
   - Front matter: `status: Pending`, `created` and `updated` set to today, `depends_on`, `related`.
   - Status history: one row, `— → Pending`, by `story-writer`.
   - **Requirements** `R-n`: what the system must do, one testable statement each.
   - **Out of scope**: what is explicitly not included.
   - **Technical details**: endpoints (method, path, request/response JSON, status codes), migration (tables, columns,
     types, constraints, `STRICT`), packages and classes, and constraints. Everything must follow AGENTS.md (REST
     conventions, ProblemDetail, SQLite rules, virtual threads).
   - **Acceptance criteria** `AC-n` in Given / When / Then. Each AC must be verifiable by an automated test or a
     concrete command. Cover the happy path, validation errors, not-found and conflict cases. Every requirement must be
     covered by at least one AC.
   - Leave *Execution evidence* and *Review* as in the template (comments only).
6. **Self-check** each story before finishing:
   - every `R-n` is covered by an AC
   - every AC is objectively verifiable
   - no implementation decisions are left open, or they are listed under Open questions
   - ID and file name match
   - the language is English throughout

## Output

Finish with a short list of the created stories in dependency order (`ID — title — depends_on`), plus any open
questions that still block a story.
