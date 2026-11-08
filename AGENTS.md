# AGENTS.md

Instructions for AI coding agents (and humans) working on this repository.
This file is the single source of truth — `CLAUDE.md` only imports it.

## Project overview

`kotlin-test` is a **REST API backend** written in **Kotlin** on **Spring Boot 4.1** and **Java 25**.
Data lives in **SQLite**, accessed with **Spring Data JDBC**; the schema is managed by **Flyway**.
**Virtual threads are enabled** (`spring.threads.virtual.enabled: true`): every HTTP request runs on its own virtual thread.
Work is planned and tracked as **stories** in [`docs/stories/`](docs/stories/README.md) (see [Work items: stories](#work-items-stories)).

## Language

**English everywhere**, even when the user talks to the agent in another language. That covers:
- every file in the repository: code, identifiers, comments, KDoc, docs, stories, skills, migrations, configuration
- commit messages and PR descriptions
- all application texts: API responses, `ProblemDetail` titles and details, validation messages, log messages

Requirements given in another language are translated into English when they are written down as stories.

## Git: agents never commit

**Agents never create or rewrite git history. There are no exceptions, even when asked.** All changes are left
uncommitted in the working tree, and **the user makes every commit**.

- **Forbidden:** `commit`, `merge`, `rebase`, `cherry-pick`, `revert`, `am`, `pull`, `push`, `tag`, `reset`,
  `commit-tree`, `update-ref`.
- **Allowed** (read-only or staging): `status`, `diff`, `log`, `show`, `blame`, `branch --list`, `add`, `restore`.
  To unstage, use `git restore --staged <file>`, not `git reset`. To list tags, use `git show-ref --tags` (`git tag` is blocked).
- No workarounds: no aliases, `-c alias.*`, scripts, or other tools that commit or push indirectly.
- **Enforcement:** the `PreToolUse` hook [.claude/hooks/block-git-history.py](.claude/hooks/block-git-history.py) blocks
  these commands in the Bash and PowerShell tools. When it blocks something, stop and tell the user what is ready to be
  committed. Don't try another route.

## Tech stack

| Component           | Version          | Where it is pinned                                   |
|---------------------|------------------|------------------------------------------------------|
| Java (toolchain)    | 25 (LTS)         | `build.gradle.kts` → `java.toolchain`                |
| Kotlin              | 2.3.21           | `build.gradle.kts` → `plugins` (matches Boot's BOM)  |
| Spring Boot         | 4.1.2            | `build.gradle.kts` → `plugins`                       |
| Spring Framework    | 7.0.x            | managed by Boot                                      |
| Spring Data JDBC    | 4.1.x            | managed by Boot (`spring-data-bom` 2026.0.x)         |
| Flyway              | 12.x             | managed by Boot                                      |
| SQLite JDBC driver  | xerial 3.53.x    | managed by Boot                                      |
| Jackson             | 3.x (`tools.jackson.*`) | managed by Boot                               |
| Gradle              | 9.8.2            | `gradle/wrapper/gradle-wrapper.properties`           |

Rules:
- **Do not bump versions or add dependencies without asking the user.**
- Never set versions for Boot-managed libraries — let the Spring Boot BOM decide.
- Keep the Kotlin plugin version equal to Boot's managed `kotlin.version` unless the user asks otherwise.

## Documentation — always use the current docs

Spring Boot 4 / Spring Framework 7 changed a lot compared to Boot 3 (and to most training data):
Jackson 3 (`tools.jackson.*` packages), modular starters (`spring-boot-starter-webmvc`, per-module `*-test` starters),
JSpecify null-safety, `@MockitoBean` instead of `@MockBean`, and more. **Check the docs below instead of relying on memory**
whenever you use an API you are not 100% sure about. The Spring links are unversioned and always point to the current release.

**Java 25**
- Docs home: https://docs.oracle.com/en/java/javase/25/
- API (Javadoc): https://docs.oracle.com/en/java/javase/25/docs/api/index.html
- Language spec: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- JDK 25 features (JEPs): https://openjdk.org/projects/jdk/25/
- Virtual threads: https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html

**Spring Boot**
- Docs home: https://docs.spring.io/spring-boot/index.html
- Reference: https://docs.spring.io/spring-boot/reference/index.html
- Kotlin support: https://docs.spring.io/spring-boot/reference/features/kotlin.html
- Task execution & virtual threads: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- SQL databases: https://docs.spring.io/spring-boot/reference/data/sql.html
- Testing: https://docs.spring.io/spring-boot/reference/testing/index.html
- Application properties: https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- Gradle plugin: https://docs.spring.io/spring-boot/gradle-plugin/index.html
- API (Javadoc): https://docs.spring.io/spring-boot/api/java/index.html
- Release notes / migration guides: https://github.com/spring-projects/spring-boot/wiki

**Spring Framework**
- Reference: https://docs.spring.io/spring-framework/reference/index.html
- Web MVC (REST controllers): https://docs.spring.io/spring-framework/reference/web/webmvc.html
- Error responses (`ProblemDetail`): https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
- Kotlin: https://docs.spring.io/spring-framework/reference/languages/kotlin.html

**Spring Data JDBC**
- Reference: https://docs.spring.io/spring-data/relational/reference/jdbc.html

**Kotlin**
- Docs home: https://kotlinlang.org/docs/home.html
- Coding conventions: https://kotlinlang.org/docs/coding-conventions.html
- Spring Boot with Kotlin: https://kotlinlang.org/docs/jvm-get-started-spring-boot.html

**Gradle**
- User guide: https://docs.gradle.org/current/userguide/userguide.html
- Toolchains: https://docs.gradle.org/current/userguide/toolchains.html

**SQLite / driver / Flyway**
- SQLite docs: https://www.sqlite.org/docs.html
- SQLite STRICT tables: https://www.sqlite.org/stricttables.html
- xerial sqlite-jdbc: https://github.com/xerial/sqlite-jdbc
- Flyway + SQLite: https://documentation.red-gate.com/fd/sqlite-277579331.html

## Commands

Use the Gradle wrapper (`./gradlew` on macOS/Linux/Git Bash, `.\gradlew.bat` in PowerShell/cmd).
The JDK 25 toolchain is auto-downloaded on first run (foojay resolver in `settings.gradle.kts`); Gradle itself can run on JDK 17+.

| Task                         | Command                                      |
|------------------------------|----------------------------------------------|
| Build + all tests            | `./gradlew build`                            |
| Tests only                   | `./gradlew test`                             |
| Single test class            | `./gradlew test --tests "com.example.kotlintest.SomeTest"` |
| Run the app (port 8080)      | `./gradlew bootRun`                          |
| Clean                        | `./gradlew clean`                            |
| Health check (app running)   | `curl http://localhost:8080/actuator/health` |

## Project layout

```
src/main/kotlin/com/example/kotlintest/
├── KotlinTestApplication.kt      # entry point
├── config/                       # cross-cutting configuration (SqliteDialect, ...)
├── common/                       # shared code: error handling, base types
└── <feature>/                    # one package per feature (package-by-feature), e.g. `book/`
    ├── BookController.kt         # REST endpoints, DTO <-> domain mapping
    ├── BookService.kt            # business logic, transactions
    ├── BookRepository.kt         # Spring Data JDBC repository
    ├── Book.kt                   # persistence entity (aggregate root)
    └── BookDtos.kt               # request/response DTOs
src/main/resources/
├── application.yaml              # main config
└── db/migration/                 # Flyway migrations (V1__init.sql, ...)
src/test/kotlin/...               # tests mirror the main package structure
src/test/resources/application-test.yaml   # `test` profile (in-memory SQLite)
data/                             # runtime SQLite DB (app.db) — gitignored, only .gitkeep is tracked
docs/stories/                     # work items: README.md (rules), TEMPLATE.md, STORY-NNN-*.md
.claude/
├── settings.json                 # project hooks registration
├── hooks/                        # PreToolUse hooks (Python 3, standard library only):
│                                 #   protect-completed-stories.py, block-git-history.py
└── skills/                       # story-writer, implementer, reviewer
```

## Kotlin & Spring conventions

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html); 4-space indent (see `.editorconfig`).
- **Constructor injection only** (primary constructor, `private val`). No field injection, no `lateinit` beans.
- Prefer `val` and immutable data. **Never use `!!`** — model nullability explicitly; the compiler runs with `-Xjsr305=strict`.
- DTOs are `data class`es and are **separate from persistence entities**. Never expose entities from controllers.
- Validate request DTOs with Jakarta Validation (`@field:NotBlank`, `@field:Size`, ...) and `@Valid` on the controller parameter.
- Errors are returned as RFC 9457 `ProblemDetail` (`spring.mvc.problemdetails.enabled: true`). Domain exceptions are mapped
  in a single `@RestControllerAdvice` in `common/`. Do not leak stack traces or SQL in responses.
- Business logic lives in services; controllers stay thin. `@Transactional` goes on service methods.
- Use `java.time` types (`Instant`, `LocalDate`); never `java.util.Date`.

### Concurrency with virtual threads

- Write **plain blocking code** (Spring MVC + JDBC). No WebFlux, no Reactor, no Kotlin coroutines in the request path.
- Never create thread pools by hand (`Executors.newFixedThreadPool`, etc.) and never pool virtual threads. Use Boot-provided
  executors (`@Async`, `@Scheduled`, `SimpleAsyncTaskExecutor`), which already run on virtual threads.
- Use `ThreadLocal` sparingly — with one thread per task, caching expensive objects in `ThreadLocal`s does not help.
- On Java 25, `synchronized` no longer pins the carrier thread (JEP 491), but holding a lock during slow I/O still serializes work.
  Prefer `java.util.concurrent` primitives and keep critical sections short.
- The real bottleneck is the **database**: the Hikari pool is small and SQLite allows **one writer at a time**.
  Keep transactions short and never call remote services inside a transaction.

## REST API conventions

- Base path `/api/v1/...`; resource names are plural nouns in kebab-case (`/api/v1/books`, `/api/v1/book-authors`).
- Standard verbs: `GET` (read), `POST` (create), `PUT` (full replace), `PATCH` (partial update), `DELETE`.
- Status codes: `200` OK, `201 Created` + `Location` header on create, `204 No Content` on delete,
  `400` for malformed or invalid input, `404` not found, `409` conflict (e.g. unique constraint).
- JSON field names are camelCase. Timestamps are ISO-8601 in UTC (`2026-01-31T12:00:00Z`).
- Collections are paginated (`?page=0&size=20&sort=name,asc`); never return unbounded lists.
- Actuator exposes only `health` and `info`.

## Persistence & SQLite rules

- **Schema changes go only through Flyway migrations** in `src/main/resources/db/migration`, named `V{n}__{description}.sql`
  (e.g. `V1__create_books.sql`). **Never edit a migration that has already been applied** — add a new one.
- Create tables as `STRICT` tables with `id INTEGER PRIMARY KEY` (an alias of the rowid, auto-generated).
- Table and column names are `snake_case`. Spring Data JDBC's default naming strategy maps `camelCase` properties to them.
- Foreign keys are enforced through the connection URL (`foreign_keys=true`); always declare them in migrations.
- SQLite has few types (`INTEGER`, `REAL`, `TEXT`, `BLOB`). Store booleans as `INTEGER` 0/1 and money as `INTEGER` minor units.
  For timestamps, choose one representation (ISO-8601 UTC `TEXT` preferred) and add a `@DataJdbcTest` round-trip test the
  first time a temporal column is introduced. The driver's default for dates is epoch milliseconds.
- `ALTER TABLE` is limited in SQLite (dropping or changing columns may need the create-copy-drop-rename pattern; see the SQLite docs).
- **Concurrency:** the DB runs in WAL mode with `busy_timeout=5000`. There is no `SELECT ... FOR UPDATE`: a write
  transaction locks the whole database, so pessimistic locks render as nothing. Use `@Version` optimistic locking where needed.
- **Dialect:** Spring Data JDBC has no built-in SQLite dialect. `config/SqliteDialect.kt` provides one: ANSI-based, with
  `LIMIT/OFFSET` paging, no-op locks, and no array support. It replaces Boot's auto-detected `jdbcDialect` bean. Extend it if you hit SQL
  that SQLite rejects; don't switch to another dialect.
- The runtime DB file is `data/app.db` (plus `-wal`/`-shm`). It is gitignored and must never be committed.
- sqlite-jdbc loads a native library. The build passes `--enable-native-access=ALL-UNNAMED` to tests and `bootRun`, and the
  boot jar manifest contains `Enable-Native-Access: ALL-UNNAMED`. When running the app any other way, pass the same flag.

## Testing

- JUnit 5 with `kotlin-test` assertions. Test names use backticks: ``fun `returns 404 for unknown book`()``.
- Pick the narrowest slice:
  - `@WebMvcTest` for controllers (with `@MockitoBean` for collaborators)
  - `@DataJdbcTest` for repositories
  - `@SpringBootTest` only for end-to-end flows
- Spring tests run with `@ActiveProfiles("test")`, which uses a shared-cache in-memory SQLite DB
  (`src/test/resources/application-test.yaml`). Flyway migrations run against it, so tests exercise the real schema.
- Every new endpoint needs tests for the happy path, validation errors and not-found/conflict cases.
- Existing guard tests must keep passing:
  - `KotlinTestApplicationTests`: the context loads with the SQLite dialect.
  - `SqliteDialectTest`: paging SQL.
  - `VirtualThreadsTest`: requests run on virtual threads.

## Work items: stories

Every feature or change is described by a **story** in `docs/stories/STORY-NNN-slug.md`. The full rules are in
[docs/stories/README.md](docs/stories/README.md), and the file format is in [docs/stories/TEMPLATE.md](docs/stories/TEMPLATE.md).
A story contains Requirements, Technical details, Acceptance criteria, Execution evidence and Review.

```
Pending ──► In Progress ──► In Review ──► Completed (immutable)
                 ▲               │
                 └───────────────┘ changes requested
```

| Step | Skill (run manually, each in a fresh context) | Transition |
|------|-----------------------------------------------|------------|
| Analyse requirements, create stories | [`/story-writer <requirements>`](.claude/skills/story-writer/SKILL.md) | → Pending |
| Implement a story, record Execution evidence | [`/implementer STORY-NNN`](.claude/skills/implementer/SKILL.md) | Pending → In Progress → In Review |
| Verify requirements and acceptance criteria | [`/reviewer STORY-NNN`](.claude/skills/reviewer/SKILL.md) | In Review → Completed, or back to In Progress |

Key rules:
- Requirements and acceptance criteria are frozen once a story leaves `Pending`. The implementer never changes them.
- Execution evidence and Review are **append-only** (`Attempt N`, `Review N`).
- **Completed stories are immutable.** Never edit them; create a follow-up story with `related: [STORY-NNN]`.
  The `.claude/hooks/protect-completed-stories.py` hook blocks Edit/Write on them. Never edit story files via shell
  commands, because the hook cannot see those.
- Agents without Claude Code skills follow the same procedures by reading the `SKILL.md` files.

## Agent workflow — definition of done

1. Read the relevant docs (see above) before using unfamiliar or Boot-4-changed APIs.
   When working on a story, follow the implementer or reviewer procedure exactly.
2. Make the change following the conventions in this file. Add a Flyway migration for every schema change.
3. Add or update tests.
4. Run `./gradlew build`. It must pass with no new warnings. Do not report work as done if it fails.
5. **Never commit or push** (see [Git: agents never commit](#git-agents-never-commit)). Leave the changes uncommitted and
   tell the user what is ready. Never stage secrets, `data/*.db*` files or `build/` output.
6. When a convention changes (new library, new pattern), update this `AGENTS.md` in the same change.
7. Claude Code hooks live in `.claude/hooks/` and are written in **Python 3, standard library only**. They are registered in
   `.claude/settings.json`.
