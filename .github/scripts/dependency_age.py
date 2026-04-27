#!/usr/bin/env python3

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


GRADLE_VERSIONS_URL = "https://services.gradle.org/versions/all"
MAVEN_SEARCH_URL = "https://search.maven.org/solrsearch/select"
DEFAULT_MIN_AGE_HOURS = 48
GRADLE_PRERELEASE_PATTERN = re.compile(r"(?:^|[.\-])(rc|milestone)(?:[.\-\d]|$)", re.IGNORECASE)


@dataclass(frozen=True)
class Candidate:
    version: str
    published_at: datetime


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Dependency age helpers for GitHub workflows.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    gradle = subparsers.add_parser("select-gradle", help="Select the newest eligible Gradle release.")
    add_common_selection_args(gradle)
    gradle.add_argument("--versions-url", default=GRADLE_VERSIONS_URL)
    gradle.add_argument("--versions-file")

    maven = subparsers.add_parser("select-maven", help="Select the newest eligible Maven artifact release.")
    add_common_selection_args(maven)
    maven.add_argument("--group-id", required=True)
    maven.add_argument("--artifact-id", required=True)
    maven.add_argument("--search-url", default=MAVEN_SEARCH_URL)
    maven.add_argument("--search-response-file")
    maven.add_argument(
        "--prerelease-pattern",
        action="append",
        default=[],
        help="Case-insensitive regex fragment used to exclude prerelease versions.",
    )

    validate = subparsers.add_parser("validate-lockfiles", help="Validate changed Gradle lockfile entries.")
    validate.add_argument("--baseline-dir", required=True)
    validate.add_argument("--current-dir", default=".")
    validate.add_argument("--metadata-file")
    validate.add_argument("--search-url", default=MAVEN_SEARCH_URL)
    validate.add_argument("--min-age-hours", type=int, default=default_min_age_hours())
    validate.add_argument("--now")
    validate.add_argument("--github-output", default=None)

    return parser.parse_args()


def add_common_selection_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--min-age-hours", type=int, default=default_min_age_hours())
    parser.add_argument("--now")
    parser.add_argument("--github-output", default=None)


def default_min_age_hours() -> int:
    try:
        return int(os.environ.get("MIN_DEPENDENCY_AGE_HOURS", DEFAULT_MIN_AGE_HOURS))
    except ValueError:
        return DEFAULT_MIN_AGE_HOURS


def now_utc(raw: str | None) -> datetime:
    if raw:
        return parse_datetime(raw)
    return datetime.now(timezone.utc)


def parse_datetime(value: Any) -> datetime:
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc)
    if isinstance(value, (int, float)):
        timestamp = float(value)
        if timestamp > 10_000_000_000:
            timestamp /= 1000.0
        return datetime.fromtimestamp(timestamp, tz=timezone.utc)
    if value is None:
        raise ValueError("timestamp is required")

    text = str(value).strip()
    if not text:
        raise ValueError("timestamp is empty")

    # Gradle buildTime compact format: 20260423130000+0000
    try:
        return datetime.strptime(text, "%Y%m%d%H%M%S%z").astimezone(timezone.utc)
    except ValueError:
        pass

    # ISO 8601: normalise Z and +HHMM → +HH:MM for fromisoformat
    text = re.sub(r"([+-])(\d{2})(\d{2})$", r"\1\2:\3", text.replace("Z", "+00:00"))
    return datetime.fromisoformat(text).astimezone(timezone.utc)


def format_datetime(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def emit_outputs(outputs: dict[str, Any], github_output: str | None) -> None:
    lines = [f"{key}={'' if value is None else value}" for key, value in outputs.items()]
    for line in lines:
        print(line)
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            for line in lines:
                handle.write(f"{line}\n")


def load_json(file_path: str | None, url: str | None) -> Any:
    if file_path:
        text = Path(file_path).read_text(encoding="utf-8")
        text = re.sub(r"(?<!:)//[^\n]*", "", text)  # strip // line comments, preserve ://
        return json.loads(text)
    if not url:
        raise ValueError("either file_path or url is required")
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.load(response)


def select_gradle_release(args: argparse.Namespace) -> int:
    cutoff = now_utc(args.now) - timedelta(hours=args.min_age_hours)
    payload = load_json(args.versions_file, args.versions_url)
    candidates: list[Candidate] = []
    for entry in payload:
        version = entry.get("version")
        build_time = entry.get("buildTime")
        if not version or not build_time:
            continue
        if any(bool(entry.get(flag)) for flag in ("snapshot", "nightly", "releaseNightly", "broken", "activeRc")):
            continue
        if entry.get("rcFor") or GRADLE_PRERELEASE_PATTERN.search(version):
            continue
        published_at = parse_datetime(build_time)
        if published_at <= cutoff:
            candidates.append(Candidate(version=version, published_at=published_at))

    return emit_selection_result(
        label="Gradle",
        cutoff=cutoff,
        github_output=args.github_output,
        candidates=candidates,
        not_found_reason=(
            f"No eligible stable Gradle release is at least {args.min_age_hours} hours old."
        ),
    )


def select_maven_release(args: argparse.Namespace) -> int:
    cutoff = now_utc(args.now) - timedelta(hours=args.min_age_hours)
    pattern = combine_patterns(args.prerelease_pattern)
    candidates: list[Candidate] = []
    for document in load_maven_documents(
        group_id=args.group_id,
        artifact_id=args.artifact_id,
        search_url=args.search_url,
        response_file=args.search_response_file,
    ):
        version = document.get("v")
        timestamp = document.get("timestamp")
        if not version or timestamp is None:
            continue
        if pattern and pattern.search(version):
            continue
        published_at = parse_datetime(timestamp)
        if published_at <= cutoff:
            candidates.append(Candidate(version=version, published_at=published_at))

    return emit_selection_result(
        label=f"{args.group_id}:{args.artifact_id}",
        cutoff=cutoff,
        github_output=args.github_output,
        candidates=candidates,
        not_found_reason=(
            f"No eligible stable release found for {args.group_id}:{args.artifact_id} "
            f"that is at least {args.min_age_hours} hours old."
        ),
    )


def combine_patterns(patterns: list[str]) -> re.Pattern[str] | None:
    non_empty = [pattern for pattern in patterns if pattern]
    if not non_empty:
        return None
    return re.compile("|".join(f"(?:{pattern})" for pattern in non_empty), re.IGNORECASE)


def load_maven_documents(
    *,
    group_id: str,
    artifact_id: str,
    search_url: str,
    response_file: str | None,
) -> list[dict[str, Any]]:
    if response_file:
        payload = load_json(response_file, None)
        return list(payload.get("response", {}).get("docs", []))

    docs: list[dict[str, Any]] = []
    start = 0
    rows = 200
    total = None
    while total is None or start < total:
        query = urllib.parse.urlencode(
            {
                "q": f'g:"{group_id}" AND a:"{artifact_id}"',
                "core": "gav",
                "rows": rows,
                "start": start,
                "wt": "json",
                "sort": "timestamp desc",
            }
        )
        payload = load_json(None, f"{search_url}?{query}")
        response = payload.get("response", {})
        total = int(response.get("numFound", 0))
        batch = list(response.get("docs", []))
        docs.extend(batch)
        if not batch:
            break
        start += len(batch)
    return docs


def emit_selection_result(
    *,
    label: str,
    cutoff: datetime,
    github_output: str | None,
    candidates: list[Candidate],
    not_found_reason: str,
) -> int:
    selected = max(candidates, key=lambda candidate: (candidate.published_at, candidate.version), default=None)
    outputs: dict[str, Any] = {
        "cutoff_at": format_datetime(cutoff),
    }
    if not selected:
        outputs.update(
            {
                "found": "false",
                "version": "",
                "published_at": "",
                "reason": not_found_reason,
            }
        )
        emit_outputs(outputs, github_output)
        print(f"::error::{not_found_reason}")
        return 1

    outputs.update(
        {
            "found": "true",
            "version": selected.version,
            "published_at": format_datetime(selected.published_at),
            "reason": "",
        }
    )
    emit_outputs(outputs, github_output)
    print(
        f"Selected latest eligible stable version for {label}: "
        f"{selected.version} (published {format_datetime(selected.published_at)}, cutoff {format_datetime(cutoff)})"
    )
    return 0


def validate_lockfiles(args: argparse.Namespace) -> int:
    cutoff = now_utc(args.now) - timedelta(hours=args.min_age_hours)
    baseline_dir = Path(args.baseline_dir)
    current_dir = Path(args.current_dir)
    metadata = load_metadata_overrides(args.metadata_file)

    changed = changed_lockfile_coordinates(baseline_dir=baseline_dir, current_dir=current_dir)
    outputs = {
        "cutoff_at": format_datetime(cutoff),
        "validated_coordinates": len(changed),
        "reverted_coordinates": 0,
    }
    if not changed:
        emit_outputs(outputs, args.github_output)
        print("No dependency version changes detected across Gradle lockfiles.")
        return 0

    gav_to_files: dict[str, set[str]] = {}
    for relative_path, gav in changed:
        gav_to_files.setdefault(gav, set()).add(relative_path)

    violations: list[tuple[str, list[str], str]] = []
    for gav in sorted(gav_to_files):
        published_at, reason = resolve_gav_timestamp(
            gav=gav,
            metadata=metadata,
            search_url=args.search_url,
        )
        affected_files = sorted(gav_to_files[gav])
        if published_at is None:
            violations.append((gav, affected_files, reason or "Unable to determine publish timestamp."))
            continue
        if published_at > cutoff:
            violations.append(
                (
                    gav,
                    affected_files,
                    (
                        f"Published at {format_datetime(published_at)}, which is newer than cutoff "
                        f"{format_datetime(cutoff)}."
                    ),
                )
            )
            continue
        print(
            f"Verified {gav} in {', '.join(affected_files)} "
            f"(published {format_datetime(published_at)}, cutoff {format_datetime(cutoff)})"
        )

    if violations:
        outputs["reverted_coordinates"] = len(violations)
        revert_violations_in_lockfiles(
            violations=violations,
            baseline_dir=baseline_dir,
            current_dir=current_dir,
        )
        for gav, affected_files, message in violations:
            for path in affected_files:
                print(f"::warning file={path}::{gav}: {message} Reverted to prior version.")

    emit_outputs(outputs, args.github_output)
    print(
        f"Validated {len(changed)} changed dependency selections against cutoff {format_datetime(cutoff)}. "
        f"{len(violations)} reverted."
    )
    return 0


def revert_violations_in_lockfiles(
    *,
    violations: list[tuple[str, list[str], str]],
    baseline_dir: Path,
    current_dir: Path,
) -> None:
    file_to_violated_gavs: dict[str, set[str]] = {}
    for gav, affected_files, _ in violations:
        for path in affected_files:
            file_to_violated_gavs.setdefault(path, set()).add(gav)

    for relative_path, violated_gavs in file_to_violated_gavs.items():
        current_path = current_dir / relative_path
        baseline_path = baseline_dir / relative_path

        # Keyed by full group:artifact:version so multiple versions of the same
        # group:artifact (e.g. com.typesafe:config:1.3.1 and com.typesafe:config:1.4.4
        # locked for different configurations) are never confused.
        baseline_by_gav = read_lockfile_lines(baseline_path) if baseline_path.exists() else {}
        current_by_gav = read_lockfile_lines(current_path)

        # Group removed baseline GAVs and violated GAVs by group:artifact.
        removed_by_ga: dict[str, list[str]] = {}
        for b in sorted(baseline_by_gav):
            if b not in current_by_gav:
                ga = ":".join(b.split(":")[:2])
                removed_by_ga.setdefault(ga, []).append(b)

        violations_by_ga: dict[str, list[str]] = {}
        for v in sorted(violated_gavs):
            ga = ":".join(v.split(":")[:2])
            violations_by_ga.setdefault(ga, []).append(v)

        # Pair each removed predecessor with the violation at the same sorted position.
        # Excess predecessors (consolidation) pile onto the last violation.
        # Violations with no corresponding predecessor are brand-new dependencies.
        predecessors_by_violated: dict[str, list[str]] = {v: [] for v in violated_gavs}
        for ga, ga_violations in violations_by_ga.items():
            for i, pred in enumerate(removed_by_ga.get(ga, [])):
                target = ga_violations[min(i, len(ga_violations) - 1)]
                predecessors_by_violated[target].append(pred)

        output_lines: list[str] = []
        for raw_line in current_path.read_text(encoding="utf-8").splitlines():
            stripped = raw_line.strip()
            coordinate = stripped.split("=", 1)[0] if "=" in stripped and not stripped.startswith("#") else None
            if coordinate and coordinate.count(":") == 2 and coordinate in violated_gavs:
                predecessors = predecessors_by_violated[coordinate]
                if predecessors:
                    for pred in predecessors:
                        output_lines.append(baseline_by_gav[pred])
                    print(f"Reverted {coordinate} to {', '.join(predecessors)} in {relative_path}")
                else:
                    print(f"Removed new dependency {coordinate} from {relative_path} (too new, no prior version)")
            else:
                output_lines.append(raw_line)

        current_path.write_text("\n".join(output_lines) + "\n", encoding="utf-8")


def load_metadata_overrides(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    return load_json(path, None)


def resolve_gav_timestamp(
    *,
    gav: str,
    metadata: dict[str, Any],
    search_url: str,
) -> tuple[datetime | None, str | None]:
    if gav in metadata:
        override = metadata[gav]
        return parse_metadata_override(gav, override)

    group_id, artifact_id, version = gav.split(":", 2)
    query = urllib.parse.urlencode(
        {
            "q": f'g:"{group_id}" AND a:"{artifact_id}" AND v:"{version}"',
            "core": "gav",
            "rows": 20,
            "wt": "json",
        }
    )
    payload = load_json(None, f"{search_url}?{query}")
    docs = payload.get("response", {}).get("docs", [])
    for document in docs:
        if document.get("v") != version:
            continue
        timestamp = document.get("timestamp")
        if timestamp is None:
            return None, "Maven Central search result did not include a publish timestamp."
        return parse_datetime(timestamp), None
    return None, "No metadata was found for this coordinate in Maven Central search."


def parse_metadata_override(gav: str, override: Any) -> tuple[datetime | None, str | None]:
    if isinstance(override, dict):
        if "reason" in override:
            return None, str(override["reason"])
        for key in ("timestamp", "published_at", "timestamp_ms"):
            if key in override:
                return parse_datetime(override[key]), None
        return None, f"Metadata override for {gav} is missing a timestamp."
    if isinstance(override, (int, float, str)):
        return parse_datetime(override), None
    return None, f"Unsupported metadata override format for {gav}."


def changed_lockfile_coordinates(*, baseline_dir: Path, current_dir: Path) -> list[tuple[str, str]]:
    changed: list[tuple[str, str]] = []
    baseline_lockfiles = collect_lockfiles(baseline_dir)
    current_lockfiles = collect_lockfiles(current_dir)

    for relative_path in sorted(set(baseline_lockfiles) | set(current_lockfiles)):
        before = baseline_lockfiles.get(relative_path, set())
        after = current_lockfiles.get(relative_path, set())
        for gav in sorted(after - before):
            changed.append((relative_path, gav))
    return changed


def read_lockfile_lines(path: Path) -> dict[str, str]:
    """Maps group:artifact:version to the full lockfile line for a given file."""
    lines: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        coordinate = line.split("=", 1)[0]
        if coordinate.count(":") != 2:
            continue
        lines[coordinate] = line
    return lines


def collect_lockfiles(root: Path) -> dict[str, set[str]]:
    lockfiles: dict[str, set[str]] = {}
    if not root.exists():
        return lockfiles
    for path in root.rglob("gradle.lockfile"):
        lockfiles[str(path.relative_to(root))] = parse_lockfile(path)
    return lockfiles


def parse_lockfile(path: Path) -> set[str]:
    return set(read_lockfile_lines(path))


def main() -> int:
    args = parse_args()
    if args.command == "select-gradle":
        return select_gradle_release(args)
    if args.command == "select-maven":
        return select_maven_release(args)
    if args.command == "validate-lockfiles":
        return validate_lockfiles(args)
    raise ValueError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    sys.exit(main())
