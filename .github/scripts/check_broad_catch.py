#!/usr/bin/env python3
"""Flag newly-added `catch (Throwable ...)` / `catch (Exception ...)` blocks in Java
diffs that neither log, rethrow, nor carry an explanatory comment.

Why this check exists
----------------------
Across code review on this repository, reviewers repeatedly had to flag the same
pattern by hand: a new broad `catch (Throwable ...)` or `catch (Exception ...)` block
that silently discards the failure (no `log.*` call, no rethrow, and no comment
explaining why swallowing is intentional). `catch (Throwable ...)` is a particular
hazard here because it also swallows JVM-fatal errors (`OutOfMemoryError`,
`StackOverflowError`, ...), not just the exception the author intended to guard
against.

This is intentionally scoped to *added* lines only (via the unified diff), not the
whole codebase: many existing `catch (Throwable ignored)` idioms in this repository
are deliberate and long-standing (e.g. reflective-call guards in decorator classes),
and rewriting them is out of scope for a lint rule. The goal is to stop the pattern
from growing, not to retroactively rewrite history.

Heuristic (kept deliberately simple and conservative, to keep false positives low):
  - Only considers added lines (lines starting with `+`, excluding `+++`) in files
    matching `*.java`, gathered from a unified diff (e.g. `git diff`).
  - Looks for a `catch` clause whose caught type includes `Throwable` or `Exception`
    (including as part of a multi-catch `A | B`).
  - Starting from the `catch` line, scans forward through the added lines that belong
    to the same contiguous added block (i.e. stops at the first non-added / context
    line, since we can only reason about lines actually introduced by this diff) up to
    a bounded window. The catch body is treated as SAFE (not flagged) if any of these
    appear in that window:
      * a call that looks like logging (`log.`, `LOG.`, `logger.`, `LOGGER.`)
      * a `throw` statement (explicit rethrow of the caught exception or another one)
      * a comment (`//` or `/*`) - treated as an explicit justification for the
        intentional swallow, matching the review-feedback pattern of asking authors to
        document *why* a catch is safe to ignore.
  - An empty catch body, or a body that only returns/assigns with no log/throw/comment
    within the window, is flagged.

This is necessarily a heuristic over unified diffs (not a true Java/AST parse), so it
is intentionally conservative: it only looks at added lines, only in changed hunks, and
treats "there's a comment in the catch body" as sufficient justification rather than
judging the quality of that justification. False negatives are expected and acceptable;
the goal is to catch the common, easy-to-miss case that recurred in review comments,
not to be a complete static analyzer.
"""

import argparse
import json
import re
import sys
from dataclasses import dataclass
from typing import List, Optional

CATCH_RE = re.compile(r"catch\s*\(([^)]*)\)")
CAUGHT_TYPE_RE = re.compile(r"\b(Throwable|Exception)\b")
LOG_CALL_RE = re.compile(r"\b(log|LOG|logger|LOGGER)\s*\.", re.IGNORECASE)
THROW_RE = re.compile(r"\bthrow\b")
COMMENT_RE = re.compile(r"//|/\*")

# How many added lines after the `catch (...)` line we scan for a log/throw/comment
# justification before giving up and flagging the catch as unjustified.
MAX_LOOKAHEAD_LINES = 25


@dataclass
class DiffHunkLine:
    file_path: str
    new_line_no: Optional[int]
    text: str
    is_added: bool


@dataclass
class Finding:
    file_path: str
    line_no: int
    caught_type: str
    snippet: str

    def format(self) -> str:
        return (
            f"{self.file_path}:{self.line_no}: broad catch of `{self.caught_type}` "
            f"with no log/rethrow/comment in the added lines: `{self.snippet.strip()}`"
        )


def is_java_file(path: str) -> bool:
    return path.endswith(".java")


def parse_unified_diff(diff_text: str) -> List[DiffHunkLine]:
    """Parses a unified diff (as produced by `git diff`) into a flat list of lines,
    tracking the current file path and new-line number, and whether each line was
    added by the diff.
    """
    lines: List[DiffHunkLine] = []
    current_file: Optional[str] = None
    new_line_no = 0
    in_hunk = False

    file_header_re = re.compile(r"^\+\+\+ b/(.+)$")
    hunk_header_re = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")

    for raw_line in diff_text.splitlines():
        file_match = file_header_re.match(raw_line)
        if file_match:
            current_file = file_match.group(1)
            in_hunk = False
            continue

        if raw_line.startswith("diff --git"):
            current_file = None
            in_hunk = False
            continue

        hunk_match = hunk_header_re.match(raw_line)
        if hunk_match:
            new_line_no = int(hunk_match.group(1))
            in_hunk = True
            continue

        if not in_hunk or current_file is None:
            continue

        if raw_line.startswith("+") and not raw_line.startswith("+++"):
            lines.append(
                DiffHunkLine(
                    file_path=current_file,
                    new_line_no=new_line_no,
                    text=raw_line[1:],
                    is_added=True,
                )
            )
            new_line_no += 1
        elif raw_line.startswith("-") and not raw_line.startswith("---"):
            # Removed line: does not exist in the new file, no line number to track.
            continue
        else:
            # Context line: present in both old and new file.
            lines.append(
                DiffHunkLine(
                    file_path=current_file,
                    new_line_no=new_line_no,
                    text=raw_line[1:] if raw_line else "",
                    is_added=False,
                )
            )
            new_line_no += 1

    return lines


def find_broad_catches(lines: List[DiffHunkLine]) -> List[Finding]:
    findings: List[Finding] = []

    for idx, line in enumerate(lines):
        if not line.is_added or not is_java_file(line.file_path):
            continue

        catch_match = CATCH_RE.search(line.text)
        if not catch_match:
            continue

        caught_types = catch_match.group(1)
        type_match = CAUGHT_TYPE_RE.search(caught_types)
        if not type_match:
            continue

        # Scan forward through subsequent *added* lines in the same file, looking for
        # a log call, a throw statement, or a comment that justifies the swallow.
        # Stop at the first line that isn't an added line from this diff (we can't
        # reason about pre-existing code we didn't see), or after MAX_LOOKAHEAD_LINES.
        justified = bool(COMMENT_RE.search(line.text))
        window_text_parts = [line.text]
        for lookahead in range(1, MAX_LOOKAHEAD_LINES + 1):
            if justified:
                break
            next_idx = idx + lookahead
            if next_idx >= len(lines):
                break
            next_line = lines[next_idx]
            if not next_line.is_added or next_line.file_path != line.file_path:
                break
            window_text_parts.append(next_line.text)
            if (
                LOG_CALL_RE.search(next_line.text)
                or THROW_RE.search(next_line.text)
                or COMMENT_RE.search(next_line.text)
            ):
                justified = True
                break
            # A lone closing brace is a reasonable proxy for "end of this catch
            # block" in the common one-line or few-line catch body case; stop
            # scanning once we see it so we don't credit unrelated code further
            # down the diff.
            if re.match(r"^\s*\}\s*$", next_line.text):
                break

        if not justified:
            findings.append(
                Finding(
                    file_path=line.file_path,
                    line_no=line.new_line_no,
                    caught_type=type_match.group(1),
                    snippet=" ".join(p.strip() for p in window_text_parts if p.strip())[:200],
                )
            )

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Flag newly-added broad catch(Throwable/Exception) blocks with no log/rethrow/comment."
    )
    parser.add_argument(
        "--diff-file",
        help="Path to a unified diff file (e.g. produced by `git diff`). Defaults to stdin.",
    )
    parser.add_argument(
        "--github-output",
        default=None,
        help="Optional path to a GITHUB_OUTPUT file to write a 'findings_json' output to.",
    )
    args = parser.parse_args()

    if args.diff_file:
        with open(args.diff_file, "r", encoding="utf-8", errors="replace") as f:
            diff_text = f.read()
    else:
        diff_text = sys.stdin.read()

    lines = parse_unified_diff(diff_text)
    findings = find_broad_catches(lines)

    for finding in findings:
        print(finding.format())

    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as f:
            payload = json.dumps(
                [
                    {
                        "file": finding.file_path,
                        "line": finding.line_no,
                        "caught_type": finding.caught_type,
                        "snippet": finding.snippet,
                    }
                    for finding in findings
                ]
            )
            f.write(f"findings_json={payload}\n")

    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
