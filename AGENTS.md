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

**Agents build, test and run the app through `scripts/app.py`** (Claude Code skill: [`/app`](.claude/skills/app/SKILL.md)).
It wraps the commands above, prints a test summary, waits until the app is healthy, and stops it without leaving
stray JVMs. Don't start `./gradlew bootRun` directly as an agent.

| Task                                   | Command                                                    |
|----------------------------------------|------------------------------------------------------------|
| Build / tests with a summary           | `python3 scripts/app.py build`, `python3 scripts/app.py test [--class NAME \| --story NNN] [--rerun]` |
| Run on the real DB (`data/app.db`, port 8080) | `python3 scripts/app.py start work`                 |
| Run on a fresh throwaway DB (`build/app/sandbox.db`, port 8081) | `python3 scripts/app.py start sandbox` |
| Stop / status / logs                   | `python3 scripts/app.py stop work\|sandbox\|all`, `status`, `logs <mode>` |

Use `sandbox` for manual checks with `curl`, and stop every mode you started before you finish.

## Project layout

```
src/main/kotlin/com/example/kotlintest/
├── KotlinTestApplication.kt      # entry point
├── config/                       # cross-cutting configuration (SqliteDialect, Instant<->TEXT converters, Clock)
├── common/                       # shared code: ApiException + ApiExceptionHandler, PageResponse, money/, idempotency/, json/, validation/
└── <feature>/                    # one package per feature (package-by-feature), e.g. `book/`
    ├── BookController.kt         # REST endpoints, DTO <-> domain mapping
    ├── BookService.kt            # business logic, transactions
    ├── BookRepository.kt         # Spring Data JDBC repository
    ├── Book.kt                   # persistence entity (aggregate root)
    └── BookDtos.kt               # request/response DTOs
src/main/resources/
├── application.yaml              # main config
└── db/migration/                 # Flyway migrations (V1__init.sql, ...)
src/test/kotlin/com/example/kotlintest/
├── acceptance/                   # black-box acceptance tests per story (Story<NNN>...AcceptanceTest.kt), locked after test review
└── ...                           # other tests mirror the main package structure
src/test/resources/application-test.yaml   # `test` profile (in-memory SQLite)
data/                             # runtime SQLite DB (app.db) — gitignored, only .gitkeep is tracked
docs/stories/                     # work items: README.md (rules), TEMPLATE.md, STORY-NNN-*.md
scripts/locked-tests.py           # hash / verify locked acceptance tests (Python 3, standard library only)
scripts/app.py                    # build, test, start/stop the app in work or sandbox mode (Python 3, stdlib only);
                                  #   runtime state and logs in build/app/
.claude/
├── settings.json                 # project hooks registration
├── hooks/                        # PreToolUse hooks (Python 3, standard library only):
│                                 #   protect-completed-stories.py, protect-locked-tests.py, block-git-history.py
└── skills/                       # story-writer, test-writer, test-reviewer, implementer, reviewer, app
```

## Kotlin & Spring conventions

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html); 4-space indent (see `.editorconfig`).
- **Constructor injection only** (primary constructor, `private val`). No field injection, no `lateinit` beans.
- Prefer `val` and immutable data. **Never use `!!`** — model nullability explicitly; the compiler runs with `-Xjsr305=strict`.
- DTOs are `data class`es and are **separate from persistence entities**. Never expose entities from controllers.
- Validate request DTOs with Jakarta Validation (`@field:NotBlank`, `@field:CodePointLength`, ...) and `@Valid` on the controller parameter.
- Text length limits count Unicode code points. Use the code-point constraint from `common/validation/`
  (`@field:CodePointLength(min = 1, max = 100)`) instead of `@Size` on `String` fields.
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

### Pagination (every collection endpoint)

- Query parameters: `page` (0-based, default `0`), `size` (default `20`, capped at `100` by
  `spring.data.web.pageable.*` in `application.yaml`), `sort` (`property,asc|desc`, default `id,asc`).
- Take a `Pageable` parameter and call `pageable.withValidatedSort(ALLOWED)` (`common/PageResponse.kt`). It rejects
  unknown sort properties with `400` (`InvalidSortPropertyException`), defaults to `id ASC` and appends `id` as a
  tie-breaker.
- Return `PageResponse.from(page, mapper)`. The response shape is always:
  `{"content": [...], "page": {"size": 20, "number": 0, "totalElements": 1, "totalPages": 1}}`.

### Money format (every money field)

- In JSON, money is a **string** in major units with exactly 2 fraction digits: `"50.00"`, `"0.05"`, `"-15.00"`.
  Requests accept only `^(0|[1-9][0-9]*)\.[0-9]{2}$` (no sign); anything else, including JSON numbers, is `400`.
- In code and in the database, money is a `Long` number of minor units (cents), stored in `INTEGER` columns.
  Convert only with `common/money/Money.kt` (`Money.parse` / `Money.format`, string and integer arithmetic).
- A money field in a request DTO is a `String?`. Annotate it with
  `@JsonDeserialize(using = StringOnlyDeserializer::class)` (`common/json/`) so JSON numbers are rejected instead of
  coerced to strings, and with `@field:MoneyAmount(min = "0.01", max = "...")` (`common/money/`), which checks the
  format and the range. Convert it with `Money.parse` after validation. With an `@JsonCreator` factory, put
  `@JsonDeserialize` on the factory parameter (see `good/GoodDtos.kt`).
- Integer request fields that must not accept `10.5` or `"10"` use `IntegerOnlyDeserializer` (`common/json/`) the same way.
- **Never** use `Double`, `Float`, SQL `REAL`/`NUMERIC` or Jackson number nodes for money. Do arithmetic on `Long`
  with `Math.addExact` / `Math.multiplyExact`.

### Idempotency-Key (every `POST` under `/api/v1`)

- Every `POST` endpoint requires the header `Idempotency-Key`: 1–255 visible ASCII characters (`0x21`–`0x7E`). It is
  ignored on other methods.
- `common/idempotency/` implements the rules for all endpoints:
  - `CachedBodyFilter` makes the body re-readable.
  - `IdempotencyInterceptor` checks the header, replays stored responses (`Idempotent-Replayed: true`), and rejects
    reused keys (`422`) and concurrent same-key requests (`409`) **before** validation.
  - `IdempotentExecutor` stores the `2xx` response in `idempotency_keys` **in the same transaction** as the
    business change. Failed requests leave no record.
- A new `POST` endpoint wraps its work in the executor and builds the full `ResponseEntity` inside it:
  ```kotlin
  @PostMapping
  fun create(@Valid @RequestBody body: CreateThingRequest, request: HttpServletRequest) =
      idempotentExecutor.execute(request) {
          val thing = ThingResponse.from(thingService.create(...))
          ResponseEntity.created(URI.create("/api/v1/things/${thing.id}")).body(thing)
      }
  ```
  Services called inside it use `@Transactional` (`REQUIRED`) and join the executor's transaction.
- The fingerprint is the method, the path without the query string, and the SHA-256 of the raw body bytes.

### Errors

- Domain errors extend `common/ApiException(status, title, detail)`. `common/ApiExceptionHandler` (the single
  `@RestControllerAdvice`, a `ResponseEntityExceptionHandler`) renders them, Spring MVC errors and a catch-all `500`
  as `application/problem+json`.
- Fixed titles (clients may rely on them):

| Status | Title                             | When                                   |
|--------|-----------------------------------|----------------------------------------|
| `400`  | `Idempotency-Key header required` | `POST` header missing or blank         |
| `400`  | `Invalid Idempotency-Key header`  | header too long or bad characters      |
| `400`  | `Invalid sort property`           | `sort` names an unsupported property   |
| `404`  | `User not found`                  | unknown user ID                        |
| `404`  | `Good not found`                  | unknown good ID                        |
| `404`  | `Transaction not found`           | unknown ledger transaction ID          |
| `409`  | `Email already in use`            | another user has this email (any case) |
| `409`  | `Concurrent modification`         | an update lost an optimistic-locking (`@Version`) race, or a SQLite lock conflict (`SQLITE_BUSY` / `SQLITE_LOCKED`) rolled the transaction back |
| `409`  | `Request in progress`             | same key is being processed            |
| `422`  | `Idempotency key reused`          | same key, different request            |

## Persistence & SQLite rules

- **Schema changes go only through Flyway migrations** in `src/main/resources/db/migration`, named `V{n}__{description}.sql`
  (e.g. `V1__create_books.sql`). **Never edit a migration that has already been applied** — add a new one.
- Create tables as `STRICT` tables with `id INTEGER PRIMARY KEY` (an alias of the rowid, auto-generated).
- Table and column names are `snake_case`. Spring Data JDBC's default naming strategy maps `camelCase` properties to them.
- Foreign keys are enforced through the connection URL (`foreign_keys=true`); always declare them in migrations.
- SQLite has few types (`INTEGER`, `REAL`, `TEXT`, `BLOB`). Store booleans as `INTEGER` 0/1 and money as `INTEGER` minor units.
- **Timestamps** are `Instant` in code and ISO-8601 UTC `TEXT` with exactly 3 fraction digits in the database
  (`2026-11-08T12:00:00.123Z`). The fixed width keeps the text sortable. `config/InstantTextConverters.kt` does the
  conversion and is registered in `SqliteDialectConfig.jdbcCustomConversions()`; `UserPersistenceTest` covers the
  round trip. Get the current time from the injected `Clock` bean and truncate it to milliseconds, so the API returns
  the same value that is stored.
- `ALTER TABLE` is limited in SQLite (dropping or changing columns may need the create-copy-drop-rename pattern; see the SQLite docs).
- **Concurrency:** the DB runs in WAL mode with `busy_timeout=5000`. There is no `SELECT ... FOR UPDATE`: a write
  transaction locks the whole database, so pessimistic locks render as nothing. Use `@Version` optimistic locking where needed.
  `ApiExceptionHandler` turns lock conflicts (`SQLITE_BUSY`, and `SQLITE_LOCKED` from the shared-cache in-memory test DB;
  see `common/SqliteErrors.kt`) into `409 Concurrent modification`, never `500`.
- **Money ledger:** `ledger/LedgerService.post` is the only code that changes `accounts.balance`. It writes both
  accounts (with `@Version`) and a balanced transaction (`transactions` + two `transaction_entries`) in the caller's
  transaction. Tests must not change balances with SQL, or the ledger invariant checks fail.
- **Dialect:** Spring Data JDBC has no built-in SQLite dialect. `config/SqliteDialect.kt` provides one: ANSI-based, with
  `LIMIT/OFFSET` paging, no-op locks, and no array support. It replaces Boot's auto-detected `jdbcDialect` bean. Extend it if you hit SQL
  that SQLite rejects; don't switch to another dialect.
- The runtime DB file is `data/app.db` (plus `-wal`/`-shm`). It is gitignored and must never be committed.
- sqlite-jdbc loads a native library. The build passes `--enable-native-access=ALL-UNNAMED` to tests and `bootRun`, and the
  boot jar manifest contains `Enable-Native-Access: ALL-UNNAMED`. When running the app any other way, pass the same flag.

## Testing

### Test-first (TDD) for stories

Every story is implemented **test-first**. The full rules are in [docs/stories/README.md](docs/stories/README.md) →
"TDD and locked tests".
1. **test-writer** writes black-box acceptance tests for every acceptance criterion **before** any production code
   exists. They live in `src/test/kotlin/com/example/kotlintest/acceptance/Story<NNN><Name>AcceptanceTest.kt`.
2. **test-reviewer** verifies them and **locks** them by recording their SHA-256 in the story.
3. **implementer** writes production code until the locked tests pass. **Locked tests are never modified, deleted,
   skipped or weakened by the implementer.** This is enforced by the `.claude/hooks/protect-locked-tests.py` hook and
   checked with `python3 scripts/locked-tests.py verify STORY-NNN`. The implementer may add its own tests in other files.

Acceptance tests are black-box:
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient` + `RestTestClient` with raw JSON
- `JdbcTemplate` with plain SQL for setup and for checking persisted state
- no imports from `com.example.kotlintest`
- test names start with the AC ID
- each test isolates its data and is deterministic

### General rules

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
A story contains Requirements, Technical details, Acceptance criteria, Acceptance tests, Test review, Execution evidence
and Review.

```
Pending ─► Test Writing ─► Test Review ─► Ready ─► In Progress ─► In Review ─► Completed (immutable)
                ▲               │                    │   ▲            │
                └───────────────┘                    │   └────────────┘
               test changes requested                │   changes requested
                ▲                                    │
                └────────────────────────────────────┘
                 test defect (only with the user's approval)
```

| Step | Skill (run manually, each in a fresh context) | Transition |
|------|-----------------------------------------------|------------|
| Analyse requirements, create stories | [`/story-writer <requirements>`](.claude/skills/story-writer/SKILL.md) | → Pending |
| Write failing acceptance tests | [`/test-writer STORY-NNN`](.claude/skills/test-writer/SKILL.md) | Pending → Test Writing → Test Review |
| Review and lock the acceptance tests | [`/test-reviewer STORY-NNN`](.claude/skills/test-reviewer/SKILL.md) | Test Review → Ready, or back to Test Writing |
| Implement until the locked tests pass | [`/implementer STORY-NNN`](.claude/skills/implementer/SKILL.md) | Ready → In Progress → In Review |
| Verify requirements, ACs and the lock | [`/reviewer STORY-NNN`](.claude/skills/reviewer/SKILL.md) | In Review → Completed, or back to In Progress |

Key rules:
- **WIP limit 1:** only one story may be active (from `Test Writing` through `In Review`) at a time.
- Requirements and acceptance criteria are frozen once a story leaves `Pending`. Only the user may change them.
- **Locked acceptance tests** can't be changed by the implementer. A suspected test defect is recorded and escalated to
  the user (see docs/stories/README.md).
- Acceptance tests, Test review, Execution evidence and Review are **append-only** (`Test design N`, `Test review N`,
  `Attempt N`, `Review N`).
- **Completed stories are immutable.** Never edit them; create a follow-up story with `related: [STORY-NNN]`.
  The `.claude/hooks/protect-completed-stories.py` hook blocks Edit/Write on them. Never edit story files via shell
  commands, because the hook cannot see those.
- Agents without Claude Code skills follow the same procedures by reading the `SKILL.md` files.

## Agent workflow — definition of done

1. Read the relevant docs (see above) before using unfamiliar or Boot-4-changed APIs.
   When working on a story, follow the procedure of your role's skill exactly.
2. Make the change following the conventions in this file. Add a Flyway migration for every schema change.
3. Add or update tests. For story work, the locked acceptance tests come first and are never changed by the implementer.
4. Run `./gradlew build`. It must pass with no new warnings. Do not report work as done if it fails. For story work,
   `python3 scripts/locked-tests.py verify STORY-NNN` must also pass.
5. **Never commit or push** (see [Git: agents never commit](#git-agents-never-commit)). Leave the changes uncommitted and
   tell the user what is ready. Never stage secrets, `data/*.db*` files or `build/` output.
6. When a convention changes (new library, new pattern), update this `AGENTS.md` in the same change.
7. Claude Code hooks live in `.claude/hooks/` and are written in **Python 3, standard library only**. They are registered in
   `.claude/settings.json`.
