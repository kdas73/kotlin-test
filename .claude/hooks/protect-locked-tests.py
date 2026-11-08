#!/usr/bin/env python3
"""PreToolUse hook (Edit, Write, MultiEdit, NotebookEdit): locked acceptance tests are read-only
while a story is in the implementation phase (Ready / In Progress / In Review).

The locked files are the ones in the "Locked tests" tables of approved test reviews in docs/stories
(see docs/stories/README.md -> "TDD and locked tests"). The parsing lives in scripts/locked-tests.py.
Exit code 2 blocks the tool call and shows stderr to the agent. Python 3 standard library only.
"""

import importlib.util
import json
import os
import re
import sys
from pathlib import Path

sys.dont_write_bytecode = True  # don't leave scripts/__pycache__ behind when importing the helper

REPO_ROOT = Path(__file__).resolve().parents[2]
GIT_BASH_DRIVE = re.compile(r"^/([A-Za-z])/")  # /c/Users/... -> C:/Users/... on Windows


def load_locked_tests_module():
    spec = importlib.util.spec_from_file_location("locked_tests", REPO_ROOT / "scripts" / "locked-tests.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def normalize(path_str: str) -> str:
    path_str = path_str.replace("\\", "/")
    if os.name == "nt":
        path_str = GIT_BASH_DRIVE.sub(lambda m: m.group(1).upper() + ":/", path_str)
    path = Path(path_str)
    if not path.is_absolute():
        path = REPO_ROOT / path
    return os.path.normcase(os.path.abspath(path))


def main() -> int:
    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8", errors="replace"))
    except (ValueError, OSError):
        return 0
    tool_input = payload.get("tool_input") if isinstance(payload, dict) else None
    if not isinstance(tool_input, dict):
        return 0
    raw_path = tool_input.get("file_path") or tool_input.get("notebook_path")
    if not isinstance(raw_path, str) or not raw_path:
        return 0

    locked = load_locked_tests_module()
    if not locked.implementation_phase_active():
        return 0
    target = normalize(raw_path)
    for rel in locked.all_locked_paths():
        if normalize(str(REPO_ROOT / rel)) == target:
            print(
                f"BLOCKED: {rel} is a locked acceptance test (docs/stories/README.md -> 'TDD and locked tests'). "
                "Locked tests cannot be changed while a story is Ready / In Progress / In Review. "
                "If the test is wrong, record a Test defect in the story and ask the user; only test-writer may "
                "change it after the user moves the story back to Test Writing.",
                file=sys.stderr,
            )
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
