#!/usr/bin/env python3
"""List the code a change makes that uses the performance-contract annotations.

The perf-review skill's contract checks (#8-#10) fire on code that never names the annotation:
a field of a type marked @NoEscape, a call into a class marked @BackgroundOnly. This prints three
lists for the reviewer to check against .agents/skills/perf-review/references/checks/contracts.md:

1. Marked elements: each contract annotation and the type, field or method it marks, plus the
   types @ImpliedFor lists on StaticLifetime and their subtypes.
2. One hop: class-level fields and methods declared with a marked type.
3. Lines that use either list: the diff's added lines, or every line of --paths. In those files a
   call through a local or parameter of a marked type (cache.getUtf8(...)) counts too.

It matches Java source with regular expressions, not a parser: a hit is a place to look, not a
finding. By default it reads the working tree, including uncommitted edits, as the review does.

Usage, from anywhere in the repository:
    python3 tooling/contract_lookup.py [--diff-base SHA] [--rev REV] [--paths FILE ...]
"""

import argparse
import os
import re
import subprocess
import sys
import textwrap

ANNOTATIONS = "NoEscape|StaticLifetime|ImpliedFor|Singleton|BackgroundOnly|ForegroundSafe"
PKG = r"datadog\.(contract|trace\.api\.function)"
ANNOTATION_DIRS = [
    "internal-api/src/main/java/datadog/contract/",
    "internal-api/src/main/java/datadog/trace/api/function/",
]
MAIN = ["*.java", ":!*/test/*", ":!*/jmh/*"]

LEADING_ANNOTATION = re.compile(r"^\s*@[\w.]+(\([^)]*\))?")
TYPE_DECL = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")
FIELD_DECL = re.compile(r"[\w>\]]\s+([A-Za-z_]\w*)\s*(?:=|;)")
METHOD_DECL = re.compile(r"(\w+)\s*\(")
COMMENT_LINE = re.compile(r"^\s*(\*|//|/\*)")


class Source:
    """Reads Java source from the working tree, or from a commit when rev is set."""

    def __init__(self, rev):
        self.rev = rev

    def grep(self, pattern, pathspecs, files_only=False):
        """git grep -E over the snapshot: (path, line number, text), or paths with files_only."""
        cmd = ["git", "grep", "-I", "-z", "-E", "-l" if files_only else "-n", "-e", pattern]
        if self.rev:
            cmd.append(self.rev)
        result = subprocess.run(cmd + ["--"] + pathspecs, capture_output=True, text=True)
        if result.returncode == 1:
            return []
        if result.returncode != 0:
            sys.exit(f"git grep failed: {result.stderr.strip()}")
        prefix = f"{self.rev}:" if self.rev else ""
        if files_only:
            return [p[len(prefix):] for p in result.stdout.split("\0") if p]
        hits = []
        for line in result.stdout.splitlines():
            path, number, text = line.split("\0", 2)
            hits.append((path[len(prefix):], int(number), text))
        return hits

    def read(self, path):
        if self.rev:
            result = subprocess.run(
                ["git", "show", f"{self.rev}:{path}"], capture_output=True, text=True)
            return result.stdout if result.returncode == 0 else ""
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                return f.read()
        except OSError:
            return ""

    def diff(self, base, pathspecs, names_only=False):
        cmd = ["git", "diff", "--name-only" if names_only else "-U0", base]
        if self.rev:
            cmd.append(self.rev)
        result = subprocess.run(cmd + ["--"] + pathspecs, capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"git diff failed: {result.stderr.strip()}")
        return result.stdout


def marked_element(lines, start):
    """The type, field or method an annotation at lines[start] sits on: (kind, name) or None."""
    for line in lines[start:start + 20]:
        while True:
            stripped = LEADING_ANNOTATION.sub("", line, count=1)
            if stripped == line:
                break
            line = stripped
        if not line.strip():
            continue
        match = TYPE_DECL.search(line)
        if match:
            return "type", match.group(1)
        match = FIELD_DECL.search(line.split("(", 1)[0])
        if match:
            return "field", match.group(1)
        match = METHOD_DECL.search(line)
        if match:
            return "method", match.group(1)
    return None


def implied_types(lines, start):
    """The X.class names an @ImpliedFor lists, which may run over several lines."""
    names = []
    for line in lines[start:start + 20]:
        names += re.findall(r"\b([A-Z]\w*)\.class\b", line)
        if ")" in line:
            break
    return names


def find_marked(source):
    """List 1: [(kind, name, annotation, location)], kind being type, field, method or implied."""
    files = set(source.grep(rf"import {PKG}\.({ANNOTATIONS});|@{PKG}\.({ANNOTATIONS})",
                            MAIN, files_only=True))
    files |= set(source.grep(rf"@({ANNOTATIONS})([[:space:](]|$)",
                             ANNOTATION_DIRS, files_only=True))
    if not files:
        return []
    marked = []
    annotation = re.compile(rf"@(?:{PKG}\.)?(?P<name>{ANNOTATIONS})\b")
    hits = source.grep(rf"^[[:space:]]*@(({PKG}\.)?({ANNOTATIONS}))([[:space:](]|$)", sorted(files))
    contents = {}
    for path, number, text in hits:
        name = annotation.search(text).group("name")
        lines = contents.setdefault(path, source.read(path).splitlines())
        location = f"{path}:{number}"
        if name == "ImpliedFor":
            marked += [("implied", t, "@ImpliedFor", location) for t in implied_types(lines, number - 1)]
            continue
        element = marked_element(lines, number - 1)
        if element:
            marked.append((element[0], element[1], f"@{name}", location))
    return marked


def find_subtypes(source, roots):
    """Every type that extends or implements one of roots, at any depth."""
    known, new, found = set(roots), set(roots), []
    declaration = re.compile(r"\b(?:class|interface)\s+(\w+)")
    while new:
        names = "|".join(sorted(new))
        pattern = (r"(class|interface)[[:space:]]+[A-Za-z0-9_]+[^{;]*(extends|implements)[^{;]*"
                   rf"(^|[^A-Za-z0-9_.])({names})([^A-Za-z0-9_]|$)")
        new = set()
        for _, _, text in source.grep(pattern, MAIN):
            match = declaration.search(text)
            if match and match.group(1) not in known:
                new.add(match.group(1))
        known |= new
        found += sorted(new)
    return found


def declaration_pattern(types, terminators):
    names = "|".join(sorted(types))
    return rf"({names})(<[^;=()]*>)?(\[\])?[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*[{terminators}]"


def find_holders(source, types):
    """List 2: names of class-level fields, and Declarer.name of methods, declared with types."""
    decl = declaration_pattern(types, "=;(")
    decl_re = re.compile(posix_to_python(decl))
    fields, methods = set(), set()
    pattern = rf"(public|protected|private|static)[^=;(]*(^|[^A-Za-z0-9_.]){decl}"
    for path, _, text in source.grep(pattern, MAIN):
        match = decl_re.search(text)
        if not match:
            continue
        declared = match.group(0)
        name = re.search(r"([A-Za-z_]\w*)\s*[=;(]$", declared).group(1)
        if declared.endswith("("):
            methods.add(f"{os.path.basename(path)[:-len('.java')]}.{name}")
        else:
            fields.add(name)
    return fields, methods


def posix_to_python(pattern):
    return pattern.replace("[[:space:]]", r"\s")


def find_locals(source, paths, types):
    """{path: names} of locals and parameters declared with a marked type in each file."""
    decl = re.compile(r"(?:^|[^\w])" + posix_to_python(declaration_pattern(types, "=;,)")))
    found = {}
    for path in paths:
        names = set()
        for match in decl.finditer(source.read(path)):
            names.add(re.search(r"([A-Za-z_]\w*)\s*[=;,)]$", match.group(0)).group(1))
        if names:
            found[path] = names
    return found


def added_lines(source, base, paths):
    """(path, line number, text) for each added line, or for every line of paths."""
    if paths:
        for path in paths:
            for number, line in enumerate(source.read(path).splitlines(), 1):
                yield path, number, line
        return
    path, number = None, 0
    for line in source.diff(base, MAIN).splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            number = int(line.split()[2].split(",")[0][1:])
        elif line.startswith("+") and path:
            yield path, number, line[1:]
            number += 1


def lookup(source, base, paths=None):
    """The three lists as printable lines."""
    out = ["== 1. Marked elements"]
    marked = find_marked(source)
    if not marked:
        return ["No contract annotations found."]
    roots = [name for kind, name, _, _ in marked if kind == "implied"]
    subtypes = find_subtypes(source, roots)
    out += [" ".join(m) for m in marked] + [f"implied {t} (subtype)" for t in subtypes]

    types = {name for kind, name, _, _ in marked if kind in ("type", "implied")} | set(subtypes)
    methods = {name for kind, name, _, _ in marked if kind == "method"}
    fields, held_methods = find_holders(source, types)
    fields |= {name for kind, name, _, _ in marked if kind == "field"}
    out += ["", "== 2. Fields and methods declared with a marked type (one hop)"]
    out.append(textwrap.fill(" ".join(sorted(fields) + sorted(held_methods)), width=100,
                             break_long_words=False, break_on_hyphens=False))

    changed = paths or [p for p in source.diff(base, MAIN, names_only=True).splitlines() if p]
    receivers = find_locals(source, changed, types)
    words = "|".join(re.escape(w) for w in sorted(types | methods | fields))
    uses = [re.compile(rf"(?:^|[^\w])({words})(?:[^\w]|$)")]
    if held_methods:
        calls = "|".join(re.escape(m) for m in sorted(held_methods))
        uses.append(re.compile(rf"(?:^|[^\w.])({calls})\s*\("))
    out += ["", "== 3. Lines that use them"]
    for path, number, line in added_lines(source, base, paths):
        if COMMENT_LINE.match(line):
            continue
        patterns = list(uses)
        if path in receivers:
            names = "|".join(re.escape(n) for n in sorted(receivers[path]))
            patterns.append(re.compile(rf"(?:^|[^\w.])({names})\s*\."))
        for pattern in patterns:
            match = pattern.search(line)
            if match:
                out.append(f"{path}:{number}: [{match.group(1)}] {line}")
                break
    return out


def default_base(rev):
    result = subprocess.run(["git", "merge-base", rev or "HEAD", "origin/master"],
                            capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("No merge base with origin/master; pass --diff-base.")
    return result.stdout.strip()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--diff-base", help="the merge base the review diffs against "
                        "(default: the merge base with origin/master)")
    parser.add_argument("--rev", help="scan this commit instead of the working tree")
    parser.add_argument("--paths", nargs="+", metavar="FILE",
                        help="a direct review of files: list 3 covers every line of them")
    args = parser.parse_args(argv)
    top = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    if top.returncode != 0:
        sys.exit("Run this inside the repository.")
    paths = [os.path.relpath(os.path.abspath(p), top.stdout.strip()) for p in args.paths or []]
    os.chdir(top.stdout.strip())
    base = None if paths else (args.diff_base or default_base(args.rev))
    print("\n".join(lookup(Source(args.rev), base, paths)))


if __name__ == "__main__":
    main()
