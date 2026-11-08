#!/usr/bin/env python3
"""PreToolUse hook (Bash, PowerShell): agents never commit or rewrite git history.

Blocks any git subcommand that creates or rewrites history (see AGENTS.md -> "Git: agents never commit").
Exit code 2 blocks the tool call and shows stderr to the agent. Python 3 standard library only.
"""

import json
import re
import sys

SUBCOMMANDS = (
    "commit-tree", "commit", "merge", "rebase", "cherry-pick", "revert",
    "am", "pull", "push", "tag", "reset", "update-ref",
)

# A shell word: quoted string or a run of non-separator characters.
VALUE = r"""(?:"[^"]*"|'[^']*'|[^\s;&|()]+)"""

GIT_HISTORY_COMMAND = re.compile(
    rf"""
    (?:^|[\s;&|({{`])                       # git in command position
    (?:[^\s;&|()'"`]*[/\\])?                # optional path prefix (/usr/bin/, C:/Git/cmd/); no trailing backslash here
    git(?:\.exe)?
    (?:\s+(?:                               # git global options before the subcommand
        -[Cc]\s+{VALUE}                     #   -C <path>, -c <key=value>
      | --(?:git-dir|work-tree|namespace|exec-path|super-prefix)(?:=|\s+){VALUE}
      | -{{1,2}}[A-Za-z][\w-]*(?:={VALUE})? #   --no-pager, -P, --flag=value
    ))*
    \s+(?:{"|".join(re.escape(s) for s in SUBCOMMANDS)})
    (?=$|[\s;&|)'"`])                       # end of the subcommand word
    """,
    re.VERBOSE | re.IGNORECASE,
)

MESSAGE = (
    "BLOCKED: agents never commit or rewrite git history (AGENTS.md -> 'Git: agents never commit'). "
    "Blocked subcommands: " + ", ".join(SUBCOMMANDS) + ". "
    "Leave the changes uncommitted; the user commits."
)


def main() -> int:
    try:
        payload = json.loads(sys.stdin.buffer.read().decode("utf-8", errors="replace"))
    except (ValueError, OSError):
        return 0
    tool_input = payload.get("tool_input") if isinstance(payload, dict) else None
    command = tool_input.get("command") if isinstance(tool_input, dict) else None
    if not isinstance(command, str):
        return 0
    if GIT_HISTORY_COMMAND.search(command):
        print(MESSAGE, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
