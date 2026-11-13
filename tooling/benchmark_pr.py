#!/usr/bin/env python3
"""Run the benchmarks a branch's changes require, and print the results for its pull request.

`.github/required-benchmarks.txt` maps hot code to the JMH benchmarks that measure it. For the files a
branch changes since its merge base, this finds the required benchmarks, builds and runs them on the
base and head commits (each in a clean worktree, on this machine), and prints a `## Benchmarks`
section for the pull request description.

    tooling/benchmark_pr.py required            # list the benchmarks this branch needs
    tooling/benchmark_pr.py run                 # run them on base and head, print the section
    tooling/benchmark_pr.py run -- -f 1 -wi 2   # extra arguments go to JMH, for a spot check
    tooling/benchmark_pr.py check ...           # the pull request check (see its --help)

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
NO_PERF_IMPACT_LABEL = "tag: no perf impact"


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


def benchmark_class(benchmark):
    """'datadog.trace.util.HashtableD1Benchmark.iterate' -> 'HashtableD1Benchmark'."""
    return benchmark.split(".")[-2]


def parse_benchmark_list(text):
    """The benchmark classes in the output of a JMH jar's `-l`."""
    return {
        benchmark_class(line.strip())
        for line in text.splitlines()
        if line.strip().count(".") >= 1 and not line.startswith("Benchmarks:")
    }


def list_classes(jar, names):
    """Which of `names` the jar contains: a class the change adds isn't on the base commit."""
    listing = subprocess.run(
        [java_command(), "-jar", str(jar), "-l", includes_regex(names)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return parse_benchmark_list(listing) & set(names)


def missing_classes(names, results):
    """The classes in `names` with no result: a benchmark that failed, or a fork that died."""
    measured = {benchmark_class(r["benchmark"]) for r in results}
    return sorted(set(names) - measured)


def run_jar(jar, names, result_file, jmh_args):
    # -foe: without it JMH exits 0 when a benchmark fails, leaving its results out of the file
    command = [java_command(), "-jar", str(jar), includes_regex(names), "-prof", "gc", "-foe", "true"]
    command += ["-rf", "json", "-rff", str(result_file), *jmh_args]
    print("+ " + " ".join(command), file=sys.stderr)
    subprocess.run(command, check=True, stdout=sys.stderr)
    return json.loads(Path(result_file).read_text())


def result_key(result):
    """Benchmark, mode and params: a run with several modes (`-bm thrpt,avgt`) has one result each."""
    params = result.get("params") or {}
    return result["benchmark"], result.get("mode", ""), tuple(sorted(params.items()))


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
    base_sha, head_sha, names, base_results, head_results, environment, jmh_args=(), new_classes=()
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
    ]
    if new_classes:
        lines.append(
            "Not on the base commit, added by this change: "
            + ", ".join(f"`{n}`" for n in sorted(new_classes))
            + "."
        )
    lines += [
        "",
        "| Benchmark | Mode | Params | Base | Head | Δ | Base B/op | Head B/op |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for key in sorted(set(base_by_key) | set(head_by_key)):
        base, head = base_by_key.get(key), head_by_key.get(key)
        params = ", ".join(f"{k}={v}" for k, v in key[2])
        lines.append(
            f"| {short_name(key[0])} | {key[1]} | {params} "
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


def check_pr(rules, changed, body, labels, changed_since):
    """Whether a pull request carries the benchmark results its changes require.

    Args:
      changed: the paths the pull request changes.
      body: its description.
      labels: its label names.
      changed_since: maps a commit to the paths changed between it and the pull request's head, or
        None when the commit isn't in the head's history.

    Returns:
      (ok, message), the message saying what is required and what is missing.
    """
    required = required_benchmarks(rules, changed)
    if not required:
        return True, "This pull request changes no code that requires benchmark results."
    names = benchmark_names(required)
    listed = ", ".join(f"`{n}`" for n in names)
    if NO_PERF_IMPACT_LABEL in labels:
        return True, f"Required benchmarks ({listed}) skipped: `{NO_PERF_IMPACT_LABEL}` label."
    marker = MARKER_RE.search(body or "")
    if not marker:
        return False, f"No `## Benchmarks` section with results for {listed}."
    recorded = set(filter(None, marker["benchmarks"].split(",")))
    missing = [n for n in names if n not in recorded]
    if missing:
        return False, "The `## Benchmarks` section has no results for " + ", ".join(
            f"`{n}`" for n in missing
        ) + "."
    since = changed_since(marker["head"])
    if since is None:
        return False, (
            f"The results are for commit `{marker['head'][:10]}`, which isn't in this pull "
            "request's history. Rerun them."
        )
    stale = sorted(p for p in since if any(rule.matches(p) for rule in rules))
    if stale:
        return False, (
            f"Benchmarked code changed after the results (commit `{marker['head'][:10]}`): "
            + ", ".join(f"`{p}`" for p in stale)
            + ". Rerun them."
        )
    return True, f"Benchmark results present for {listed}."


def command_check(args):
    base = git("merge-base", args.base, args.head)

    def changed_since(sha):
        try:
            full = git("rev-parse", "--verify", f"{sha}^{{commit}}")
            git("merge-base", "--is-ancestor", full, args.head)
        except subprocess.CalledProcessError:
            return None
        return changed_paths(full, args.head)

    ok, message = check_pr(
        load_rules(),
        changed_paths(base, args.head),
        Path(args.body_file).read_text(),
        [label for label in args.labels.split(",") if label],
        changed_since,
    )
    print(message)
    return 0 if ok else 1


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
    expected = {base: [], head: []}  # the classes each commit has and must produce results for
    worktrees = []
    try:
        for sha in (base, head):
            worktree = scratch / sha[:10]
            git("worktree", "add", "--detach", str(worktree), sha)
            worktrees.append(worktree)
            for project, project_names in required.items():
                jar = build_jar(worktree, project)
                present = sorted(list_classes(jar, project_names))
                if sha == head and len(present) < len(project_names):
                    missing = ", ".join(sorted(set(project_names) - set(present)))
                    print(f"error: {project} at HEAD has no benchmark {missing}", file=sys.stderr)
                    return 1
                expected[sha] += present
                if present:
                    out = scratch / f"{sha[:10]}-{project_dir(project).replace('/', '_')}.json"
                    results[sha] += run_jar(jar, present, out, args.jmh_args)
    finally:
        for worktree in worktrees:
            subprocess.run(["git", "worktree", "remove", "--force", str(worktree)])
        shutil.rmtree(scratch, ignore_errors=True)
    for sha, label in ((base, "base"), (head, "head")):
        missing = missing_classes(expected[sha], results[sha])
        if missing:
            print(f"error: no {label} results for {', '.join(missing)}", file=sys.stderr)
            return 1
    new_classes = sorted(set(names) - set(expected[base]))
    print(
        render_section(
            base,
            head,
            names,
            results[base],
            results[head],
            environment_line(),
            args.jmh_args,
            new_classes,
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
    check = sub.add_parser("check", help="whether a pull request carries the results it requires")
    check.add_argument("--base", required=True, help="the pull request's base commit")
    check.add_argument("--head", required=True, help="the pull request's head commit")
    check.add_argument("--body-file", required=True, help="a file holding its description")
    check.add_argument("--labels", default="", help="its labels, comma-separated")
    args = parser.parse_args(argv)
    commands = {"required": command_required, "run": command_run, "check": command_check}
    return commands[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
