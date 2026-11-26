#!/usr/bin/env python3
"""Reject newly added test/JMH code that calls String.format(...) without an explicit Locale.

String.format("...", args) formats with the JVM default locale, so e.g. `%05d` renders Arabic-Indic digits
under `ar-EG`. In tests and benchmarks that silently changes the generated data (hashes, probe counts,
expected strings) from one machine to another. Pass `Locale.ROOT` (or use `Strings.format` where
internal-api is available), exactly as the main sources already must (see gradle/forbiddenApiFilters/main.txt).

forbiddenApis is not enabled on test source sets, and turning it on would fail on every existing call, so
only the lines added between <base> and <head> are inspected, never the existing code base.

Only `String.format` calls whose first argument is a string literal are reported: that is unambiguously the
default-locale overload, so there are no false positives from `String.format(locale, "...")`.
A call that intentionally uses the default locale can say so with a `locale-default-ok: <reason>` comment on
the same line or the line above.

Usage: check_default_locale_format.py [<base> [<head>]]   (defaults: HEAD^1 HEAD)
"""

import re
import subprocess
import sys

# src/test/java, src/jmh/java, src/latestDepTest/java, src/test/java17, ...
TEST_OR_JMH_JAVA = re.compile(r"(?:^|/)src/(?:jmh|[A-Za-z0-9]*[Tt]est[A-Za-z0-9]*)/java[0-9]*/.+\.java$")
# `\s` also spans line breaks, so `String.format(\n    "..."` is found too
DEFAULT_LOCALE_FORMAT = re.compile(r'\bString\s*\.\s*format\s*\(\s*"')
SUPPRESSION = "locale-default-ok"
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def git(*args):
    result = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def added_lines(base, head):
    """{path: set of added line numbers} for added/modified test and JMH Java files."""
    diff = git("diff", "-U0", "--no-color", "--no-renames", "--diff-filter=AM", f"{base}..{head}", "--", "*.java")
    added, path = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
            if path is not None and not TEST_OR_JMH_JAVA.search(path):
                path = None
            if path is not None:
                added.setdefault(path, set())
            continue
        hunk = HUNK.match(line)
        if hunk and path is not None:
            start, count = int(hunk.group(1)), int(hunk.group(2) or "1")
            added[path].update(range(start, start + count))
    return {p: lines for p, lines in added.items() if lines}


def is_comment(line):
    stripped = line.lstrip()
    return stripped.startswith(("//", "*", "/*"))


def find_problems(base, head):
    problems = []
    for path, lines in sorted(added_lines(base, head).items()):
        source = git("show", f"{head}:{path}")
        rows = source.split("\n")
        for match in DEFAULT_LOCALE_FORMAT.finditer(source):
            number = source.count("\n", 0, match.start()) + 1
            if number not in lines or is_comment(rows[number - 1]):
                continue
            nearby = rows[number - 1] + "\n" + (rows[number - 2] if number > 1 else "")
            if SUPPRESSION in nearby:
                continue
            problems.append(f"{path}:{number}: {rows[number - 1].strip()}")
    return problems


def main(argv):
    base = argv[1] if len(argv) > 1 else "HEAD^1"
    head = argv[2] if len(argv) > 2 else "HEAD"
    problems = find_problems(base, head)
    for problem in problems:
        print(f"error: String.format without an explicit Locale: {problem}")
    if problems:
        print(
            "\nString.format(\"...\", args) uses the JVM default locale, which changes numeric output on some "
            "machines.\nPass Locale.ROOT: String.format(Locale.ROOT, \"...\", args), or use Strings.format where "
            "internal-api is available.\nIf the default locale is intended, add a `locale-default-ok: <reason>` "
            "comment on the same line or the line above."
        )
        return 1
    print("No String.format without an explicit Locale in added test/JMH code.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
