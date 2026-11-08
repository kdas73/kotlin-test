#!/usr/bin/env python3
"""Locked acceptance tests: hash and verify the tests that test-reviewer approved for a story.

Usage (from the repository root):
    python3 scripts/locked-tests.py hash <test-file>...    # print "Locked tests" table rows for a story
    python3 scripts/locked-tests.py verify STORY-NNN       # check the story's locked tests are unchanged

The Locked tests table lives in the story's last "### Test review N — ... — Verdict: Approved" entry
(see docs/stories/README.md -> "TDD and locked tests"). Hashes are SHA-256 over the file content with CRLF
normalised to LF, so line-ending conversion by git does not count as a change.
Python 3 standard library only. This module is also imported by .claude/hooks/protect-locked-tests.py.
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STORIES_DIR = REPO_ROOT / "docs" / "stories"

# Statuses in which the implementer works and locked tests must not change.
IMPLEMENTATION_STATUSES = {"Ready", "In Progress", "In Review"}

STATUS_LINE = re.compile(r"^status:\s*([^#]*?)\s*(?:#.*)?$")
TEST_REVIEW_HEADING = re.compile(r"^###\s+Test review\s+\d+\b.*?Verdict:\s*(Approved|Changes requested)", re.IGNORECASE)
ANY_HEADING = re.compile(r"^#{2,3}\s")
LOCKED_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*([0-9a-f]{64})\s*\|\s*$")
STORY_FILE = re.compile(r"^STORY-\d{3,}-.*\.md$")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


def file_hash(path: Path) -> str:
    """SHA-256 of the file content with CRLF normalised to LF."""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def story_status(text: str) -> str | None:
    """The `status` value from the leading front-matter block, or None."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    for line in lines[1:]:
        if line.strip() == "---":
            return None
        match = STATUS_LINE.match(line)
        if match:
            return match.group(1).strip()
    return None


def locked_tests(text: str) -> dict[str, str]:
    """{repo-relative path: sha256} from the last Approved test review of a story (empty if none)."""
    result: dict[str, str] = {}
    current: dict[str, str] | None = None
    text = HTML_COMMENT.sub("", text)  # template examples live in HTML comments
    for line in text.splitlines():
        heading = TEST_REVIEW_HEADING.match(line)
        if heading:
            current = {} if heading.group(1).lower() == "approved" else None
            if current is not None:
                result = current  # a later Approved review supersedes earlier ones
            continue
        if ANY_HEADING.match(line):
            current = None
            continue
        if current is not None:
            row = LOCKED_ROW.match(line.strip())
            if row:
                current[row.group(1).strip()] = row.group(2)
    return result


def story_files() -> list[Path]:
    if not STORIES_DIR.is_dir():
        return []
    return sorted(p for p in STORIES_DIR.iterdir() if p.is_file() and STORY_FILE.match(p.name))


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def find_story(story_id: str) -> Path | None:
    matches = [p for p in story_files() if p.name.upper().startswith(story_id.upper() + "-")]
    return matches[0] if len(matches) == 1 else None


def implementation_phase_active() -> bool:
    """True if any story is Ready / In Progress / In Review."""
    return any(story_status(read_text(p)) in IMPLEMENTATION_STATUSES for p in story_files())


def all_locked_paths() -> set[str]:
    """Repo-relative paths locked by any story (its last Approved test review)."""
    paths: set[str] = set()
    for p in story_files():
        paths.update(locked_tests(read_text(p)).keys())
    return paths


def to_repo_relative(path: Path) -> str:
    return path.resolve().relative_to(REPO_ROOT).as_posix()


def cmd_hash(args: list[str]) -> int:
    if not args:
        print("usage: locked-tests.py hash <test-file>...", file=sys.stderr)
        return 2
    print("| File | SHA-256 |")
    print("|------|---------|")
    for arg in args:
        path = Path(arg)
        if not path.is_absolute():
            path = Path.cwd() / path
        if not path.is_file():
            print(f"not a file: {arg}", file=sys.stderr)
            return 2
        print(f"| `{to_repo_relative(path)}` | {file_hash(path)} |")
    return 0


def cmd_verify(args: list[str]) -> int:
    if len(args) != 1:
        print("usage: locked-tests.py verify STORY-NNN", file=sys.stderr)
        return 2
    story = find_story(args[0])
    if story is None:
        print(f"story not found (or ambiguous): {args[0]}", file=sys.stderr)
        return 2
    locked = locked_tests(read_text(story))
    if not locked:
        print(f"{story.name}: no Locked tests table in an Approved test review", file=sys.stderr)
        return 2
    failures = 0
    for rel, expected in sorted(locked.items()):
        path = REPO_ROOT / rel
        if not path.is_file():
            state = "MISSING"
        elif file_hash(path) != expected:
            state = "MODIFIED"
        else:
            state = "OK"
        failures += state != "OK"
        print(f"{state:8} {rel}")
    print(f"{story.stem}: {len(locked) - failures}/{len(locked)} locked tests unchanged")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    commands = {"hash": cmd_hash, "verify": cmd_verify}
    if not argv or argv[0] not in commands:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    return commands[argv[0]](argv[1:])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
