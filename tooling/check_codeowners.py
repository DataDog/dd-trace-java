#!/usr/bin/env python3
"""Reject redundant rooted, literal file entries in .github/CODEOWNERS.

Only tracked files are candidates: directory and wildcard rules may intentionally
cover future files. Each candidate must change the owners from the last matching
preceding rule. This is a redundancy check, not an owner-access/syntax validator.
"""

import os
from pathlib import Path
import re
import subprocess
import sys


def segment_matches(pattern, value):
    """Match * and ? with dynamic programming, without regex backtracking."""
    reachable = [True] + [False] * len(value)
    tokens = iter(pattern)
    for token in tokens:
        escaped = token == "\\"
        if escaped:
            token = next(tokens, "\\")
        following = [False] * (len(value) + 1)
        if token == "*" and not escaped:
            following[0] = reachable[0]
            for index in range(1, len(value) + 1):
                following[index] = reachable[index] or following[index - 1]
        else:
            for index, char in enumerate(value, 1):
                following[index] = reachable[index - 1] and (
                    (token == "?" and not escaped) or token == char
                )
        reachable = following
    return reachable[-1]


def path_matches(pattern, path):
    """Match CODEOWNERS segments in polynomial time and bounded working space.

    Every rule participates in precedence, including rules below directory rules.
    A terminal /* matches only direct children, as documented by GitHub.
    """
    if pattern == "/":
        return False
    parts = pattern.strip("/").split("/")
    values = path.split("/")
    anchored = "/" in pattern.rstrip("/")
    reachable = {0} if anchored else set(range(len(values)))
    for index, part in enumerate(parts):
        if not reachable:
            return False
        if part == "**":
            # A trailing /** requires a descendant; an interior ** may be empty.
            start = min(reachable) + (index == len(parts) - 1)
            reachable = set(range(start, len(values) + 1))
        else:
            reachable = {
                position + 1 for position in reachable
                if position < len(values) and segment_matches(part, values[position])
            }
    if pattern.endswith("/"):
        return any(position < len(values) for position in reachable)
    if pattern.endswith("/*"):
        return len(values) in reachable
    return bool(reachable)


def redundant_entries(contents, tracked_files):
    """Return (redundant line, inherited line, pattern) tuples, in file order."""
    preceding = []
    findings = []
    for number, raw in enumerate(contents.splitlines(), 1):
        line = re.split(r"(?<!\\)#", raw, maxsplit=1)[0].strip()
        match = re.fullmatch(r"((?:\\.|[^\s])+)(?:\s+(.*))?", line)
        if not match:
            continue
        pattern, owner_text = match.groups()
        # GitHub skips unsupported negation and character-class patterns.
        if pattern.startswith(("!", r"\#")) or "[" in pattern or "]" in pattern:
            continue
        owners = frozenset((owner_text or "").split())
        if (pattern.startswith("/") and not pattern.endswith("/")
                and not any(char in pattern for char in "*?\\")
                and pattern[1:] in tracked_files):
            for inherited_pattern, inherited_owners, inherited_line in reversed(preceding):
                if path_matches(inherited_pattern, pattern[1:]):
                    if inherited_owners == owners:
                        findings.append((number, inherited_line, pattern))
                    break
        preceding.append((pattern, owners, number))
    return findings


def main():
    root = Path(__file__).resolve().parents[1]
    tracked = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True,
    ).stdout
    findings = redundant_entries(
        (root / ".github/CODEOWNERS").read_text(encoding="utf-8"),
        set(os.fsdecode(tracked).split("\0")),
    )
    for number, inherited_line, pattern in findings:
        print(f".github/CODEOWNERS:{number}: redundant entry {pattern}; "
              f"same owners already inherited from line {inherited_line}. Remove this entry.",
              file=sys.stderr)
    if not findings:
        print("No redundant explicit file entries in CODEOWNERS.")
    return bool(findings)


if __name__ == "__main__":
    sys.exit(main())
