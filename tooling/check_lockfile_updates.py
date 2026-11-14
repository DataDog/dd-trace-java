#!/usr/bin/env python3
"""Reject changes that alter Gradle dependencies without touching any gradle.lockfile.

Dependency locking is enabled for every module (see buildSrc dd-trace-java.dependency-locking), so a change
that adds, removes or re-versions an external dependency must come with regenerated lockfiles:

    ./gradlew resolveAndLockAll --write-locks

Only what changed between <base> and <head> is inspected, never the existing code base.

Usage: check_lockfile_updates.py [<base> [<head>]]   (defaults: HEAD^1 HEAD)
A change is reported only when it contains no gradle.lockfile change at all (any lockfile update is taken
as "regenerated") and it either
  - changes the resolved coordinates/version of a gradle/libs.versions.toml library or bundle that is
    referenced by the build script of a module that has a gradle.lockfile, or
  - adds/removes/changes an external dependency declaration in the build script of such a module.
Build-logic only entries (plugins, buildSrc/build-logic) are not locked and are never reported.
"""

import re
import subprocess
import sys

CATALOG = "gradle/libs.versions.toml"
LOCKFILE = "gradle.lockfile"
LOCK_COMMAND = "./gradlew resolveAndLockAll --write-locks"
# Build logic is not covered by dependency locking
UNLOCKED_PREFIXES = ("buildSrc/", "build-logic/")

CONFIGURATION = (
    r"[A-Za-z0-9_]*(?:[Ii]mplementation|[Aa]pi|[Cc]ompileOnly|[Cc]ompileOnlyApi|[Rr]untimeOnly|"
    r"[Aa]nnotationProcessor)"
)
# `testImplementation group: 'a', name: 'b'`, `api 'a:b:1'`, `implementation(libs.foo)`, ...
DECLARATION = re.compile(r"^\s*" + CONFIGURATION + r"\s*[ (]")
NOT_EXTERNAL = re.compile(r"\bproject\s*\(|\bprojects\.|\bfiles\s*\(|\bfileTree\s*\(|\bgradleApi\s*\(|\blocalGroovy\s*\(")


def git(*args, check=True):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def parse_catalog(text):
    """Return ({alias: 'module@version'}, {bundle: frozenset(aliases)}) with versions resolved."""
    versions, libraries, bundles = {}, {}, {}
    section = None
    pending_bundle = None
    for raw in text.splitlines():
        line = re.sub(r"\s+#.*$", "", raw).strip()
        if not line:
            continue
        header = re.match(r"^\[(\w+)\]$", line)
        if header:
            section, pending_bundle = header.group(1), None
            continue
        if section == "bundles":
            if pending_bundle is not None:
                pending_bundle[1].append(line)
                if "]" in line:
                    bundles[pending_bundle[0]] = pending_bundle[1]
                    pending_bundle = None
                continue
            match = re.match(r"^([\w.-]+)\s*=\s*\[(.*)$", line)
            if match:
                body = [match.group(2)]
                if "]" in match.group(2):
                    bundles[match.group(1)] = body
                else:
                    pending_bundle = (match.group(1), body)
            continue
        match = re.match(r"^([\w.-]+)\s*=\s*(.+)$", line)
        if not match:
            continue
        alias, value = match.group(1), match.group(2).strip()
        if section == "versions":
            versions[alias] = value
        elif section == "libraries":
            libraries[alias] = value
    resolved = {}
    for alias, value in libraries.items():
        ref = re.search(r"version\.ref\s*=\s*\"([^\"]+)\"", value)
        if ref:
            value = re.sub(r"version\.ref\s*=\s*\"[^\"]+\"", "version = " + versions.get(ref.group(1), "?"), value)
        resolved[alias] = re.sub(r"\s+", " ", value)
    bundle_members = {}
    for name, parts in bundles.items():
        bundle_members[name] = frozenset(re.findall(r"\"([^\"]+)\"", " ".join(parts)))
    return resolved, bundle_members


def alias_to_accessor(alias):
    return "libs." + re.sub(r"[-_]", ".", alias)


def changed_catalog_accessors(base, head):
    """Accessors (libs.foo.bar / libs.bundles.baz) whose resolved definition changed."""
    old = parse_catalog(git("show", f"{base}:{CATALOG}", check=False))
    new = parse_catalog(git("show", f"{head}:{CATALOG}", check=False))
    changed_aliases = {a for a in set(old[0]) | set(new[0]) if old[0].get(a) != new[0].get(a)}
    accessors = {alias_to_accessor(a) for a in changed_aliases}
    for name in set(old[1]) | set(new[1]):
        members_old, members_new = old[1].get(name), new[1].get(name)
        if members_old != members_new or (members_new and members_new & changed_aliases):
            accessors.add("libs.bundles." + re.sub(r"[-_]", ".", name))
    return accessors


def is_module_script(path):
    name = path.rsplit("/", 1)[-1]
    return name in ("build.gradle", "build.gradle.kts") and not path.startswith(UNLOCKED_PREFIXES)


def locked_module_dirs(head):
    out = git("ls-tree", "-r", "--name-only", head)
    return {p[: -len(LOCKFILE)] for p in out.splitlines() if p.endswith("/" + LOCKFILE) or p == LOCKFILE}


def changed_declarations(base, head, path):
    """Added/removed external dependency declaration lines of one build script."""
    diff = git("diff", "-U0", "--no-color", f"{base}..{head}", "--", path)
    lines = []
    for line in diff.splitlines():
        if line[:1] in "+-" and not line.startswith(("+++", "---")):
            body = line[1:]
            if DECLARATION.match(body) and not NOT_EXTERNAL.search(body) and not body.strip().startswith(("//", "#")):
                lines.append(line)
    return lines


def find_problems(base, head):
    changed = git("diff", "--name-only", "--no-renames", f"{base}..{head}").splitlines()
    if any(p == LOCKFILE or p.endswith("/" + LOCKFILE) for p in changed):
        return []
    problems = []
    locked_dirs = locked_module_dirs(head)
    if CATALOG in changed:
        accessors = changed_catalog_accessors(base, head)
        if accessors:
            scripts = [p for p in git("ls-tree", "-r", "--name-only", head).splitlines()
                       if is_module_script(p) and p[: -len(p.rsplit("/", 1)[-1])] in locked_dirs]
            used = set()
            for script in scripts:
                text = git("show", f"{head}:{script}", check=False)
                used.update(a for a in accessors if re.search(re.escape(a) + r"(?![\w])", text))
            if used:
                problems.append(f"{CATALOG}: changes {', '.join(sorted(used))}, used by modules with a {LOCKFILE}")
    for path in changed:
        if is_module_script(path) and path[: -len(path.rsplit("/", 1)[-1])] in locked_dirs:
            if changed_declarations(base, head, path):
                problems.append(f"{path}: external dependency declaration added, removed or changed")
    return problems


def main(argv):
    base = argv[1] if len(argv) > 1 else "HEAD^1"
    head = argv[2] if len(argv) > 2 else "HEAD"
    problems = find_problems(base, head)
    for problem in problems:
        print(f"::error title=Lockfiles not regenerated::{problem}")
        print(f"error: {problem}")
    if problems:
        print(f"\nDependencies changed but no {LOCKFILE} was updated. Regenerate the lockfiles of all affected "
              f"modules and commit them in the same PR:\n\n  {LOCK_COMMAND}\n\n"
              "See CONTRIBUTING.md#dependency-changes and docs/how_to_work_with_gradle.md#updating-lock-files.\n"
              "If the change provably cannot alter any lockfile, add [ci: SKIP_LOCKFILE_CHECK] to the last "
              "commit message.")
        return 1
    print("No dependency change without a lockfile update.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
