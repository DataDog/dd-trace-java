#!/usr/bin/env python3
"""Run the benchmarks a branch's changes require, and print the results for its pull request.

`.github/required-benchmarks.txt` maps hot code to the JMH benchmarks that measure it. For the files a
branch changes since its merge base, this finds the required benchmarks, builds and runs them on the
base and head commits (each in a clean worktree, on this machine), and prints a `## Benchmarks`
section for the pull request description.

    tooling/benchmark_pr.py required            # list the benchmarks this branch needs
    tooling/benchmark_pr.py run                 # run them on base and head, print the section
    tooling/benchmark_pr.py run -- -f 1 -wi 2   # extra arguments go to JMH, for a spot check

The section starts with a marker comment recording the base and head commits and the benchmarks
run, so a reader (or a check) can tell which commit the results describe.
"""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile

MAPPING = ".github/required-benchmarks.txt"
MARKER_RE = re.compile(
    r"<!--\s*benchmark-pr:\s*base=(?P<base>[0-9a-f]{7,40})\s+head=(?P<head>[0-9a-f]{7,40})"
    r"\s+benchmarks=(?P<benchmarks>[A-Za-z0-9_,]*)\s*-->"
)
ALLOC_METRIC = "gc.alloc.rate.norm"


class Rule:
    """One mapping line: files matching `pattern` require `benchmarks` from Gradle `project`."""

    def __init__(self, line_number, pattern, project, benchmarks):
        self.line_number = line_number
        self.pattern = pattern
        self.project = project
        self.benchmarks = benchmarks
        self.regex = glob_to_regex(pattern)

    def matches(self, path):
        return self.regex.fullmatch(path) is not None


def glob_to_regex(pattern):
    """`*` matches within a path segment, `**` across segments; everything else is literal."""
    out = []
    i = 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile("".join(out))


def parse_mapping(text):
    """The rules in a mapping file. Raises ValueError naming the line of a malformed rule."""
    rules = []
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split()
        if len(fields) < 3 or not fields[1].startswith(":"):
            raise ValueError(
                f"{MAPPING}:{number}: expected '<pattern> <:project> <Benchmark>...', got: {raw!r}"
            )
        rules.append(Rule(number, fields[0], fields[1], fields[2:]))
    return rules


def required_benchmarks(rules, changed_paths):
    """{project: sorted benchmark classes} required by the changed paths."""
    required = {}
    for path in changed_paths:
        for rule in rules:
            if rule.matches(path):
                required.setdefault(rule.project, set()).update(rule.benchmarks)
    return {project: sorted(names) for project, names in sorted(required.items())}


def benchmark_names(required):
    return sorted({name for names in required.values() for name in names})


def project_dir(project):
    """':dd-java-agent:agent-tooling' -> 'dd-java-agent/agent-tooling'."""
    return project.lstrip(":").replace(":", "/")


def includes_regex(names):
    """A JMH include pattern for whole benchmark classes, whatever their package."""
    return r"(^|\.)(" + "|".join(re.escape(n) for n in names) + r")\."


def git(*args, cwd=None):
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def upstream_remote():
    for line in git("remote", "-v").splitlines():
        name, url = line.split()[:2]
        if re.search(r"DataDog/dd-trace-java(\.git)?$", url):
            return name
    return "origin"


def merge_base(base_ref):
    ref = base_ref or f"{upstream_remote()}/master"
    return git("merge-base", "HEAD", ref)


def changed_paths(base, head="HEAD"):
    return [p for p in git("diff", "--name-only", base, head).splitlines() if p]


def load_rules(root="."):
    return parse_mapping((Path(root) / MAPPING).read_text())


def java_command():
    java = os.environ.get("JMH_JAVA")
    if java:
        return java
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def build_jar(worktree, project):
    subprocess.run(["./gradlew", f"{project}:jmhJar", "-q"], cwd=worktree, check=True)
    jars = sorted((Path(worktree) / project_dir(project) / "build" / "libs").glob("*jmh*.jar"))
    if not jars:
        raise RuntimeError(f"no JMH jar built for {project} in {worktree}")
    return jars[-1]


def run_jar(jar, names, result_file, jmh_args):
    command = [java_command(), "-jar", str(jar), includes_regex(names), "-prof", "gc"]
    command += ["-rf", "json", "-rff", str(result_file), *jmh_args]
    print("+ " + " ".join(command), file=sys.stderr)
    subprocess.run(command, check=True, stdout=sys.stderr)
    return json.loads(Path(result_file).read_text())


def result_key(result):
    params = result.get("params") or {}
    return result["benchmark"], tuple(sorted(params.items()))


def short_name(benchmark):
    """'datadog.trace.util.HashtableD1Benchmark.iterate' -> 'HashtableD1Benchmark.iterate'."""
    return ".".join(benchmark.split(".")[-2:])


def format_number(value):
    """Four significant digits, with k/M/G for large values: 11410000 -> '11.41M'."""
    for scale, suffix in ((1e9, "G"), (1e6, "M"), (1e3, "k")):
        if abs(value) >= scale * 10:
            return f"{value / scale:.4g}{suffix}"
    return f"{value:.4g}"


def format_score(metric):
    if metric is None:
        return "–"
    error = metric.get("scoreError")
    score = format_number(metric["score"])
    if isinstance(error, (int, float)) and error == error:  # NaN when there are too few samples
        score += f" ± {format_number(error)}"
    return f"{score} {metric.get('scoreUnit', '')}".strip()


def format_alloc(result):
    metric = (result or {}).get("secondaryMetrics", {}).get(ALLOC_METRIC)
    return "–" if metric is None else f"{metric['score']:.1f}"


def format_delta(base, head):
    if base is None or head is None or not base["primaryMetric"]["score"]:
        return "–"
    b = base["primaryMetric"]["score"]
    h = head["primaryMetric"]["score"]
    return f"{(h - b) / b * 100:+.1f}%"


def render_section(
    base_sha, head_sha, names, base_results, head_results, environment, jmh_args=()
):
    """The `## Benchmarks` section: marker, conditions, and one row per benchmark and params."""
    settings = "each benchmark's own settings"
    if jmh_args:
        settings += ", overridden by `" + " ".join(jmh_args) + "` (a spot check, not a full run)"
    base_by_key = {result_key(r): r for r in base_results}
    head_by_key = {result_key(r): r for r in head_results}
    lines = [
        "## Benchmarks",
        "",
        f"<!-- benchmark-pr: base={base_sha} head={head_sha} benchmarks={','.join(names)} -->",
        f"Base `{base_sha[:10]}` vs head `{head_sha[:10]}`, run one after the other on the same "
        f"machine with {settings} and `-prof gc`. {environment}",
        "",
        "| Benchmark | Params | Base | Head | Δ | Base B/op | Head B/op |",
        "|---|---|---|---|---|---|---|",
    ]
    for key in sorted(set(base_by_key) | set(head_by_key)):
        base, head = base_by_key.get(key), head_by_key.get(key)
        params = ", ".join(f"{k}={v}" for k, v in key[1])
        lines.append(
            f"| {short_name(key[0])} | {params} "
            f"| {format_score(base and base['primaryMetric'])} "
            f"| {format_score(head and head['primaryMetric'])} "
            f"| {format_delta(base, head)} | {format_alloc(base)} | {format_alloc(head)} |"
        )
    return "\n".join(lines) + "\n"


def environment_line():
    version = subprocess.run(
        [java_command(), "-version"], capture_output=True, text=True
    ).stderr.splitlines()
    jdk = version[1].strip() if len(version) > 1 else (version[0] if version else "unknown JDK")
    return f"JDK: {jdk}. Machine: {platform.system()} {platform.machine()}, {os.cpu_count()} CPUs."


def command_required(args):
    base = merge_base(args.base)
    required = required_benchmarks(load_rules(), changed_paths(base))
    if not required:
        print("No required benchmarks: the branch changes no file in " + MAPPING)
        return 0
    for project, names in required.items():
        print(f"{project}: {' '.join(names)}")
    return 0


def command_run(args):
    base = merge_base(args.base)
    head = git("rev-parse", "HEAD")
    required = required_benchmarks(load_rules(), changed_paths(base, head))
    if not required:
        print("No required benchmarks: the branch changes no file in " + MAPPING, file=sys.stderr)
        return 0
    names = benchmark_names(required)
    scratch = Path(tempfile.mkdtemp(prefix="benchmark-pr-"))
    results = {base: [], head: []}
    worktrees = []
    try:
        for sha in (base, head):
            worktree = scratch / sha[:10]
            git("worktree", "add", "--detach", str(worktree), sha)
            worktrees.append(worktree)
            for project, project_names in required.items():
                jar = build_jar(worktree, project)
                out = scratch / f"{sha[:10]}-{project_dir(project).replace('/', '_')}.json"
                results[sha] += run_jar(jar, project_names, out, args.jmh_args)
    finally:
        for worktree in worktrees:
            subprocess.run(["git", "worktree", "remove", "--force", str(worktree)])
        shutil.rmtree(scratch, ignore_errors=True)
    print(
        render_section(
            base, head, names, results[base], results[head], environment_line(), args.jmh_args
        )
    )
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (
        ("required", "list the benchmarks the branch's changes require"),
        ("run", "run them on the merge base and HEAD, and print the PR section"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("--base", help="base ref (default: the upstream master's merge base)")
    sub.choices["run"].add_argument("jmh_args", nargs="*", help="extra JMH arguments, after --")
    args = parser.parse_args(argv)
    return {"required": command_required, "run": command_run}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
