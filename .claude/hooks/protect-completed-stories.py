#!/usr/bin/env python3
"""PreToolUse hook (Edit, Write, MultiEdit, NotebookEdit): Completed stories are immutable.

Blocks edits to docs/stories/*.md files whose front matter says `status: Completed`
(see docs/stories/README.md -> "Immutability"). Exit code 2 blocks the tool call and shows stderr to the agent.
Python 3 standard library only.
"""

import json
import os
import re
import sys
from pathlib import Path

STORY_PATH = re.compile(r"(?:^|/)docs/stories/[^/]+\.md$")
COMPLETED = re.compile(r"^status:\s*Completed(?:\s|#|$)")
GIT_BASH_DRIVE = re.compile(r"^/([A-Za-z])/")  # /c/Users/... -> C:/Users/... on Windows


def is_completed(path: Path) -> bool:
    """True if the leading `---` front-matter block contains `status: Completed`."""
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return False
    if not lines or lines[0].strip() != "---":
        return False
    for line in lines[1:]:
        if line.strip() == "---":
            return False
        if COMPLETED.match(line):
            return True
    return False


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

    normalized = raw_path.replace("\\", "/")
    if not STORY_PATH.search(normalized):
        return 0
    if os.name == "nt":
        normalized = GIT_BASH_DRIVE.sub(lambda m: m.group(1).upper() + ":/", normalized)

    path = Path(normalized)
    if not path.is_file():  # new files are always allowed
        return 0
    if is_completed(path):
        match = re.match(r"STORY-\d+", path.stem)
        story_id = match.group(0) if match else path.stem
        print(
            f"BLOCKED: {path.stem} is Completed and immutable (docs/stories/README.md). "
            f"Do not edit it; create a follow-up story with related: [{story_id}] instead.",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
