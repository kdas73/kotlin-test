#!/usr/bin/env python3
"""Build, test and run the application in different modes.

Usage (paths are resolved from the repository root, so it works from any directory):
    python3 scripts/app.py build [--skip-tests]
    python3 scripts/app.py test [--class NAME | --story NNN] [--rerun]
    python3 scripts/app.py start {work|sandbox} [--port N] [--timeout SECONDS]
    python3 scripts/app.py stop {work|sandbox|all}
    python3 scripts/app.py status
    python3 scripts/app.py logs {work|sandbox} [--lines N]

Run modes:
    work     the app on the persistent database data/app.db (default port 8080)
    sandbox  the app on a fresh throwaway database build/app/sandbox.db (default port 8081). The database is
             deleted on every start; data/app.db is never touched.

Both modes can run at the same time. The app is started with `gradlew bootRun` in the background; the script waits
until /actuator/health is UP, reads the app's PID from /actuator/info, and keeps a state file and the log in
build/app/. `stop` kills the app JVM and the Gradle client, so no stray JVMs are left behind (also on Windows, where
the JVM started by the Gradle daemon is not a child of the Gradle client).

Python 3 standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = REPO_ROOT / "build" / "app"
TEST_RESULTS_DIR = REPO_ROOT / "build" / "test-results" / "test"
WORK_DB = REPO_ROOT / "data" / "app.db"
SANDBOX_DB = STATE_DIR / "sandbox.db"

IS_WINDOWS = os.name == "nt"
GRADLEW = REPO_ROOT / ("gradlew.bat" if IS_WINDOWS else "gradlew")
BASE_PACKAGE = "com.example.kotlintest"

# Same connection options as spring.datasource.url in src/main/resources/application.yaml.
DB_URL_OPTIONS = "foreign_keys=true&journal_mode=WAL&busy_timeout=5000"

DEFAULT_PORTS = {"work": 8080, "sandbox": 8081}
MAX_FAILURES_SHOWN = 20


# --------------------------------------------------------------------------------------------------------------------
# Gradle: build and test
# --------------------------------------------------------------------------------------------------------------------

def gradle(args: list[str]) -> int:
    print(f"$ {GRADLEW.name} {' '.join(args)}", flush=True)
    return subprocess.call([str(GRADLEW), *args], cwd=REPO_ROOT)


def cmd_build(args: argparse.Namespace) -> int:
    started = time.time()
    code = gradle(["build", "-x", "test"] if args.skip_tests else ["build"])
    if not args.skip_tests:
        print_test_summary(started)
    print(f"\nbuild: {'SUCCESSFUL' if code == 0 else f'FAILED (exit code {code})'}")
    return code


def story_test_pattern(story: str) -> str:
    match = re.fullmatch(r"(?:STORY-?)?0*(\d{1,3})", story.strip(), re.IGNORECASE)
    if not match:
        raise SystemExit(f"Invalid story '{story}'. Use for example 8, 008 or STORY-008.")
    return f"{BASE_PACKAGE}.acceptance.Story{int(match.group(1)):03d}*"


def cmd_test(args: argparse.Namespace) -> int:
    gradle_args = ["test"]
    if args.story:
        gradle_args += ["--tests", story_test_pattern(args.story)]
    elif args.test_class:
        name = args.test_class
        gradle_args += ["--tests", name if "." in name else f"*.{name}"]
    if args.rerun:
        gradle_args.append("--rerun")
    started = time.time()
    code = gradle(gradle_args)
    print_test_summary(started)
    print(f"\ntests: {'PASSED' if code == 0 else f'FAILED (exit code {code})'}")
    return code


def print_test_summary(since: float) -> None:
    """Prints the JUnit XML results written by this run (files older than the run are ignored)."""
    files = sorted(
        f for f in TEST_RESULTS_DIR.glob("TEST-*.xml") if f.stat().st_mtime >= since - 1
    ) if TEST_RESULTS_DIR.is_dir() else []
    print("\n=== Test summary ===")
    if not files:
        print("No test results were written by this run (the test task was probably UP-TO-DATE or did not run).")
        print("Use `test --rerun` to force the tests to run.")
        return

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    failed: list[str] = []
    for file in files:
        try:
            suite = ET.parse(file).getroot()
        except ET.ParseError as e:
            print(f"  (could not parse {file.name}: {e})")
            continue
        counts = {key: int(suite.get(key, "0")) for key in totals}
        for key, value in counts.items():
            totals[key] += value
        print(
            f"  {suite.get('name', file.stem)}: {counts['tests']} tests, {counts['failures']} failures, "
            f"{counts['errors']} errors, {counts['skipped']} skipped"
        )
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                message = (problem.get("message") or problem.text or "").strip().splitlines()
                first_line = message[0][:200] if message else ""
                failed.append(f"{case.get('classname')} > {case.get('name')}: {first_line}")

    print(
        f"  TOTAL: {totals['tests']} tests, {totals['failures']} failures, {totals['errors']} errors, "
        f"{totals['skipped']} skipped"
    )
    if failed:
        print("\nFailed tests:")
        for line in failed[:MAX_FAILURES_SHOWN]:
            print(f"  - {line}")
        if len(failed) > MAX_FAILURES_SHOWN:
            print(f"  ... and {len(failed) - MAX_FAILURES_SHOWN} more (see build/reports/tests/test/index.html)")


# --------------------------------------------------------------------------------------------------------------------
# Run modes: start / stop / status / logs
# --------------------------------------------------------------------------------------------------------------------

def state_file(mode: str) -> Path:
    return STATE_DIR / f"{mode}.json"


def log_file(mode: str) -> Path:
    return STATE_DIR / f"{mode}.log"


def read_state(mode: str) -> dict | None:
    try:
        return json.loads(state_file(mode).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def get_json(url: str, timeout: float = 2.0) -> dict | None:
    """GET a JSON document; also returns the body of HTTP error responses (health is 503 when DOWN)."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except ValueError:
            return None
    except (OSError, ValueError):
        return None


def health(port: int) -> str | None:
    body = get_json(f"http://127.0.0.1:{port}/actuator/health")
    return body.get("status") if body else None


def port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def pid_listening_on(port: int) -> int | None:
    """Best-effort lookup of the PID that listens on a local TCP port."""
    try:
        if IS_WINDOWS:
            output = subprocess.run(
                ["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True, check=False
            ).stdout
            for line in output.splitlines():
                parts = line.split()
                if len(parts) >= 5 and parts[3].upper() == "LISTENING" and parts[1].endswith(f":{port}"):
                    return int(parts[4])
        else:
            output = subprocess.run(
                ["lsof", "-t", f"-iTCP:{port}", "-sTCP:LISTEN"], capture_output=True, text=True, check=False
            ).stdout
            pids = [int(p) for p in output.split()]
            return pids[0] if pids else None
    except (OSError, ValueError):
        return None
    return None


def process_alive(pid: int) -> bool:
    if IS_WINDOWS:
        output = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True, check=False
        ).stdout
        return re.search(rf"\b{pid}\b", output) is not None
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def kill_tree(pid: int | None, graceful_seconds: float = 15.0) -> None:
    """Stops a process and its children. POSIX: SIGTERM first (graceful shutdown), SIGKILL after a timeout."""
    if not pid:
        return
    if IS_WINDOWS:
        # Console JVMs ignore a non-forced taskkill; SQLite in WAL mode is safe against a forced stop.
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + graceful_seconds
    while time.monotonic() < deadline:
        if not process_alive(pid):
            return
        time.sleep(0.5)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def kill_gradle_client(pid: int | None) -> None:
    if not pid:
        return
    if IS_WINDOWS:
        kill_tree(pid)
        return
    try:
        os.killpg(pid, signal.SIGTERM)  # started with start_new_session=True, so its PID is its process group ID
    except (ProcessLookupError, PermissionError):
        pass


def tail(path: Path, lines: int) -> str:
    try:
        text = path.read_bytes().decode("utf-8", errors="replace")
    except OSError:
        return f"(no log file {path})"
    return "\n".join(text.splitlines()[-lines:])


def rel(path: Path) -> str:
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return str(path)


def spawn_detached(command: list[str], env: dict[str, str], log) -> subprocess.Popen:
    common = dict(cwd=REPO_ROOT, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    if not IS_WINDOWS:
        return subprocess.Popen(command, start_new_session=True, **common)
    flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    try:
        # Leave the caller's job object, if allowed, so the app survives the shell that started it.
        return subprocess.Popen(command, creationflags=flags | subprocess.CREATE_BREAKAWAY_FROM_JOB, **common)
    except OSError:
        return subprocess.Popen(command, creationflags=flags, **common)


def cmd_start(args: argparse.Namespace) -> int:
    mode: str = args.mode
    port: int = args.port or DEFAULT_PORTS[mode]

    state = read_state(mode)
    if state:
        if health(state["port"]) == "UP":
            print(f"{mode} is already running: {state['url']} (app pid {state.get('app_pid')}).")
            print(f"Stop it first with: python3 scripts/app.py stop {mode}")
            return 0
        print(f"{mode}: removing stale state from an earlier run.")
        stop_from_state(mode, state, quiet=True)

    if port_in_use(port):
        owner = pid_listening_on(port)
        print(f"ERROR: port {port} is already in use" + (f" by pid {owner}" if owner else "") + ".")
        print("Stop that process or start with another port: --port N")
        return 1

    STATE_DIR.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["SERVER_PORT"] = str(port)
    env["MANAGEMENT_INFO_PROCESS_ENABLED"] = "true"  # /actuator/info then reports the app's PID
    if mode == "sandbox":
        for suffix in ("", "-wal", "-shm"):
            Path(f"{SANDBOX_DB}{suffix}").unlink(missing_ok=True)
        env["SPRING_DATASOURCE_URL"] = f"jdbc:sqlite:{SANDBOX_DB.as_posix()}?{DB_URL_OPTIONS}"
        database = SANDBOX_DB
    else:
        env.pop("SPRING_DATASOURCE_URL", None)  # use application.yaml (data/app.db)
        database = WORK_DB

    log_path = log_file(mode)
    print(f"Starting {mode} on port {port} (database {rel(database)}); log: {rel(log_path)}", flush=True)
    with open(log_path, "wb") as log:
        process = spawn_detached([str(GRADLEW), "bootRun", "--console=plain"], env, log)

    deadline = time.monotonic() + args.timeout
    while True:
        if process.poll() is not None:
            print(f"ERROR: Gradle exited with code {process.returncode} before the app was up. Last log lines:\n")
            print(tail(log_path, 40))
            return 1
        if health(port) == "UP":
            break
        if time.monotonic() > deadline:
            print(f"ERROR: the app was not UP after {args.timeout} s. Last log lines:\n")
            print(tail(log_path, 40))
            kill_tree(pid_listening_on(port))
            kill_gradle_client(process.pid)
            return 1
        time.sleep(1)

    info = get_json(f"http://127.0.0.1:{port}/actuator/info") or {}
    app_pid = (info.get("process") or {}).get("pid") or pid_listening_on(port)
    state = {
        "mode": mode,
        "port": port,
        "url": f"http://localhost:{port}",
        "database": rel(database),
        "log": rel(log_path),
        "app_pid": app_pid,
        "gradle_pid": process.pid,
        "started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    state_file(mode).write_text(json.dumps(state, indent=2), encoding="utf-8")

    print(f"\n{mode} is UP: {state['url']}  (app pid {app_pid}, gradle pid {process.pid})")
    print(f"  database: {state['database']}" + ("  (fresh, deleted on every start)" if mode == "sandbox" else ""))
    print(f"  health:   {state['url']}/actuator/health")
    print(f"  log:      {state['log']}")
    print(f"  stop:     python3 scripts/app.py stop {mode}")
    return 0


def stop_from_state(mode: str, state: dict, quiet: bool = False) -> bool:
    port = state.get("port", DEFAULT_PORTS[mode])
    kill_tree(state.get("app_pid"))
    kill_gradle_client(state.get("gradle_pid"))
    deadline = time.monotonic() + 30
    while port_in_use(port) and time.monotonic() < deadline:
        time.sleep(0.5)
    state_file(mode).unlink(missing_ok=True)
    stopped = not port_in_use(port)
    if not quiet:
        if stopped:
            print(f"{mode}: stopped (port {port} is free).")
        else:
            owner = pid_listening_on(port)
            print(f"WARNING: {mode}: port {port} is still in use" + (f" by pid {owner}" if owner else "") + ".")
    return stopped


def cmd_stop(args: argparse.Namespace) -> int:
    modes = list(DEFAULT_PORTS) if args.mode == "all" else [args.mode]
    ok = True
    for mode in modes:
        state = read_state(mode)
        if state is None:
            print(f"{mode}: not running (no state file).")
            continue
        ok = stop_from_state(mode, state) and ok
    return 0 if ok else 1


def cmd_status(_: argparse.Namespace) -> int:
    for mode, default_port in DEFAULT_PORTS.items():
        state = read_state(mode)
        if state is None:
            line = f"{mode}: not running"
            if port_in_use(default_port):
                owner = pid_listening_on(default_port)
                line += f" (but port {default_port} is in use" + (f" by pid {owner}" if owner else "")
                line += ", not started by this script)"
            print(line)
            continue
        status = health(state["port"]) or "DOWN"
        print(
            f"{mode}: {status}  {state['url']}  database {state['database']}  app pid {state.get('app_pid')}  "
            f"started {state.get('started_at')}  log {state['log']}"
        )
        if status != "UP":
            print(f"  stale state; clean up with: python3 scripts/app.py stop {mode}")
    return 0


def cmd_logs(args: argparse.Namespace) -> int:
    print(tail(log_file(args.mode), args.lines))
    return 0


# --------------------------------------------------------------------------------------------------------------------

def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    parser = argparse.ArgumentParser(prog="app.py", description="Build, test and run the application.")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("build", help="./gradlew build (compile, all tests, boot jar)")
    p.add_argument("--skip-tests", action="store_true", help="build without running the tests")
    p.set_defaults(func=cmd_build)

    p = sub.add_parser("test", help="run all tests, one class, or one story's acceptance tests")
    which = p.add_mutually_exclusive_group()
    which.add_argument("--class", dest="test_class", metavar="NAME", help="simple or fully qualified class name")
    which.add_argument("--story", metavar="NNN", help="acceptance tests of a story, e.g. 8 or STORY-008")
    p.add_argument("--rerun", action="store_true", help="run the tests even if Gradle considers them up to date")
    p.set_defaults(func=cmd_test)

    p = sub.add_parser("start", help="start the app in the background and wait until it is UP")
    p.add_argument("mode", choices=list(DEFAULT_PORTS))
    p.add_argument("--port", type=int, help="HTTP port (default: work 8080, sandbox 8081)")
    p.add_argument("--timeout", type=int, default=180, help="seconds to wait for the app to be UP (default 180)")
    p.set_defaults(func=cmd_start)

    p = sub.add_parser("stop", help="stop a running mode (or all)")
    p.add_argument("mode", choices=[*DEFAULT_PORTS, "all"])
    p.set_defaults(func=cmd_stop)

    p = sub.add_parser("status", help="show which modes are running")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("logs", help="print the last lines of a mode's log")
    p.add_argument("mode", choices=list(DEFAULT_PORTS))
    p.add_argument("--lines", type=int, default=50)
    p.set_defaults(func=cmd_logs)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
