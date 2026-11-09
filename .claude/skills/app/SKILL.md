---
name: app
description: Build the project, run its tests (all, one class, or one story's acceptance tests), and start, stop or inspect the application in "work" mode (persistent data/app.db, port 8080) or "sandbox" mode (fresh throwaway database, port 8081). Use it whenever you need to build or test, or to run the app and check endpoints with curl, and to stop it again afterwards.
argument-hint: "[build [--skip-tests] | test [--class NAME | --story NNN] [--rerun] | start work|sandbox [--port N] | stop work|sandbox|all | status | logs work|sandbox]"
---

# App: build, test and run

Builds, tests and runs this application through one helper script, [scripts/app.py](../../../scripts/app.py)
(Python 3, standard library only). The script handles the Gradle wrapper, waits until the app is healthy, keeps state
and logs in `build/app/`, and stops the app without leaving stray JVMs.

**Arguments:** $ARGUMENTS

## Modes

| Mode        | What it does | Database | Default port |
|-------------|--------------|----------|--------------|
| `build`     | `./gradlew build`: compile, all tests, boot jar; prints a test summary | — | — |
| `test`      | `./gradlew test`: all tests, one class (`--class`), or one story's acceptance tests (`--story`); prints a test summary with failed tests | in-memory (`test` profile) | — |
| `work`      | runs the app for real use | `data/app.db` (persistent) | `8080` |
| `sandbox`   | runs the app for manual checks | `build/app/sandbox.db`, **deleted on every start** | `8081` |

`work` and `sandbox` can run at the same time.

## Commands

Run from the repository root. Use `python3`; on Windows use `python` if `python3` is not available.

| Task | Command |
|------|---------|
| Build + all tests | `python3 scripts/app.py build` |
| Build without tests | `python3 scripts/app.py build --skip-tests` |
| All tests | `python3 scripts/app.py test` |
| One test class | `python3 scripts/app.py test --class UserApiTest` (simple or fully qualified name) |
| A story's acceptance tests | `python3 scripts/app.py test --story 8` (also `008` or `STORY-008`) |
| Force tests to run | add `--rerun` |
| Start the app | `python3 scripts/app.py start sandbox` or `start work` (`--port N`, `--timeout S`) |
| Stop the app | `python3 scripts/app.py stop sandbox` / `stop work` / `stop all` |
| What is running | `python3 scripts/app.py status` |
| Last log lines | `python3 scripts/app.py logs sandbox --lines 100` |

## Procedure

1. **Pick the command from the arguments.** Map `$ARGUMENTS` to one of the commands above. If there are no arguments,
   run `status` and show the user the table of commands.
2. **Run it with the Bash tool.**
   - `build` and `test` can take several minutes: pass a `timeout` of `600000`.
   - `start` returns after the app is UP (or fails with the last log lines). The app keeps running in the background;
     don't use `run_in_background` for the script itself.
3. **Report the result.**
   - `build` / `test`: the result line and the test summary (totals, and failed tests if any).
   - `start`: the URL, the database, the log path and the stop command.
   - On a failure, show the relevant log lines. Use `logs <mode>` for more.

## Rules

- **Prefer `sandbox` for manual checks** (curl against endpoints, reviews, experiments). `work` writes to the real
  `data/app.db`; use it only when the user asks for it.
- **Stop what you started.** Before you finish your task, run `stop` for every mode you started, unless the user asked
  you to leave the app running. Check with `status`.
- If `start` reports a busy port, don't kill the unknown process. Tell the user, or start on another port with
  `--port N`.
- Never pass `data/*.db*` or `build/` output to `git add`.
- The script is the only supported way for agents to start the app. Don't start `./gradlew bootRun` directly: its JVM
  is a child of the Gradle daemon and is easily left running.

## How it works (for troubleshooting)

- `start` runs `gradlew bootRun` in the background with `SERVER_PORT` and `MANAGEMENT_INFO_PROCESS_ENABLED=true`, and
  for `sandbox` also `SPRING_DATASOURCE_URL` pointing at `build/app/sandbox.db`. It waits for `/actuator/health` to be
  `UP` and reads the app's PID from `/actuator/info`.
- State: `build/app/<mode>.json` (port, PIDs, database, log). Log: `build/app/<mode>.log`.
- `stop` kills the app JVM and the Gradle client (`taskkill /T /F` on Windows; `SIGTERM`, then `SIGKILL` elsewhere),
  waits until the port is free, and removes the state file. A stale state file (the app already died) is cleaned up by
  `stop` or by the next `start`.
- The test summary reads only the JUnit XML files written by the current run (`build/test-results/test/`). If Gradle
  skips the test task as up to date, the script says so; add `--rerun`.
