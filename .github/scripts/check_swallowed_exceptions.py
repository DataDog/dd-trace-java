#!/usr/bin/env python3
"""Fails CI when a pull request *introduces* a catch block that silently drops an
exception: no logging, no rethrow, no metric, not even a comment explaining why
it is safe to ignore.

This has shown up repeatedly in code review on this repository, for example:
  - a catch block added for a new API-compatibility probe that swallowed every
    failure with no diagnostic trail, fixed by adding a `log.debug(...)` call
    consistent with a sibling branch in the same PR;
  - a catch block for a lambda class-transform failure that, unlike every other
    catch site added in the same feature, dropped the exception with zero
    logging, hiding a silent fallback to unmodified bytecode.

SpotBugs already ships a detector for exactly this pattern (`DroppedException`,
a.k.a. the `DE_MIGHT_IGNORE`/`DE_MIGHT_DROP` bug patterns), but it is currently
listed in `gradle/spotbugs.gradle`'s `omitVisitors` for the whole repository.
Turning it on wholesale would fail the build on ~400 pre-existing catch blocks
(mostly deliberate, JVM-compatibility-probe style `catch (Throwable ignored) {}`
blocks), which the guardrails for this kind of change explicitly warn against.

Instead, this script re-implements the same narrow check (empty catch body, no
comment, no "ignored"-style variable name) but scopes it to only the lines a
pull request actually adds, using `git diff` against the PR's merge-base. This
gives the same enforcement for new code without requiring a repo-wide cleanup
or a SpotBugs baseline/suppression file.

A catch block is allowed when any of the following is true:
  - its body is non-empty once comments are stripped (it logs, rethrows,
    increments a counter, sets a flag, etc.);
  - its body contains a comment (even if otherwise empty), which is the
    existing repository convention for explaining an intentional no-op catch;
  - the caught exception's variable name signals intent, e.g. `ignored`,
    `ignore`, `expected`, or `suppressed` (also already used throughout the
    codebase for exactly this purpose).

Usage:
    check_swallowed_exceptions.py --merge-base <sha> [--head <sha>] [--repo-root <path>]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# Matches `catch (<Type>[| <Type>...] <name>) { <body> }` including multi-catch.
# Deliberately simple/regex-based (not a real Java parser): it only needs to find
# literal catch blocks whose braces are not nested, which covers the vast
# majority of real-world empty/near-empty catch blocks with a low false-positive
# rate. Anything more complex (e.g. a catch body containing a nested try with its
# own braces) safely falls through as "not a match" rather than mis-firing.
CATCH_PATTERN = re.compile(
    r"catch\s*\(\s*(?:final\s+)?[\w.$]+(?:\s*\|\s*[\w.$]+)*\s+(\w+)\s*\)\s*\{([^{}]*)\}",
    re.DOTALL,
)

# Variable names that signal "this is intentionally ignored", matching existing
# convention already used throughout dd-trace-java (e.g. `catch (Throwable ignored)`).
ALLOWED_NAME_PATTERN = re.compile(r"^(ignored|ignore|expected|suppressed)\w*$", re.IGNORECASE)


@dataclass(frozen=True)
class Violation:
    path: str
    line: int
    exception_var: str


def changed_java_files(merge_base: str, head: str, repo_root: Path) -> list[str]:
    output = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACM", f"{merge_base}...{head}", "--", "*.java"],
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [line.strip() for line in output.splitlines() if line.strip()]


def added_line_numbers(merge_base: str, head: str, path: str, repo_root: Path) -> set[int]:
    """Returns the set of line numbers (in the post-change file) that this diff adds."""
    diff_output = subprocess.run(
        ["git", "diff", "--unified=0", f"{merge_base}...{head}", "--", path],
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout

    added: set[int] = set()
    hunk_header = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
    current_line = None
    for line in diff_output.splitlines():
        match = hunk_header.match(line)
        if match:
            current_line = int(match.group(1))
            continue
        if current_line is None:
            continue
        if line.startswith("+") and not line.startswith("+++"):
            added.add(current_line)
            current_line += 1
        elif line.startswith("-") and not line.startswith("---"):
            continue
        else:
            current_line += 1
    return added


def find_violations_in_file(content: str, added_lines: set[int]) -> list[Violation]:
    violations: list[Violation] = []
    for match in CATCH_PATTERN.finditer(content):
        exception_var = match.group(1)
        body = match.group(2)

        if ALLOWED_NAME_PATTERN.match(exception_var):
            continue

        has_comment = "//" in body or "/*" in body
        if has_comment:
            continue

        stripped = re.sub(r"//.*", "", body)
        stripped = re.sub(r"/\*.*?\*/", "", stripped, flags=re.DOTALL)
        if stripped.strip() != "":
            continue

        # The catch block drops the exception. Only flag it if the PR actually
        # introduced this specific block, i.e. at least one of its lines
        # (the `catch (...)` line through the closing `}`) is a newly added line.
        start_line = content.count("\n", 0, match.start()) + 1
        end_line = content.count("\n", 0, match.end()) + 1
        if added_lines.isdisjoint(range(start_line, end_line + 1)):
            continue

        violations.append(Violation(path="", line=start_line, exception_var=exception_var))
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--merge-base", required=True, help="Base commit/ref to diff against (e.g. the PR's target branch).")
    parser.add_argument("--head", default="HEAD", help="Head commit/ref to diff (defaults to HEAD).")
    parser.add_argument("--repo-root", default=".", help="Path to the git repository root.")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    all_violations: list[Violation] = []

    for rel_path in changed_java_files(args.merge_base, args.head, repo_root):
        full_path = repo_root / rel_path
        if not full_path.is_file():
            # File was deleted/renamed away; nothing to scan.
            continue

        added = added_line_numbers(args.merge_base, args.head, rel_path, repo_root)
        if not added:
            continue

        try:
            content = full_path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue

        for violation in find_violations_in_file(content, added):
            all_violations.append(Violation(path=rel_path, line=violation.line, exception_var=violation.exception_var))

    if all_violations:
        print("Found new catch block(s) that silently drop an exception:\n")
        for v in sorted(all_violations, key=lambda v: (v.path, v.line)):
            print(f"  {v.path}:{v.line}: catch (... {v.exception_var}) has an empty body")
        print(
            "\nEach catch block above swallows its exception with no logging, no rethrow, "
            "and no explanatory comment. Either:\n"
            "  - log or otherwise act on the exception (e.g. log.debug(\"...\", e)),\n"
            "  - rename the variable to `ignored`/`expected` AND/OR add a short comment "
            "explaining why dropping it is intentional and safe.\n"
        )
        return 1

    print("No new swallowed-exception catch blocks found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
