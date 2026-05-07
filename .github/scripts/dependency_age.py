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



@dataclass(frozen=True)
class Candidate:
    version: str
    published_at: datetime


# Entry point for GitHub Actions workflows
# select-gradle: get newest Gradle release that is at least MIN_DEPENDENCY_AGE_HOURS hours old
# select-maven: get newest Maven artifact release that is at least MIN_DEPENDENCY_AGE_HOURS hours old
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

    return parser.parse_args()


# add shared args used by select-gradle and select-maven
def add_common_selection_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--min-age-hours", type=int, default=default_min_age_hours())
    parser.add_argument("--now")
    parser.add_argument("--current-version", default=None)
    parser.add_argument("--github-output", default=None)


# get MIN_DEPENDENCY_AGE_HOURS from environment variable; default is 48 hours
def default_min_age_hours() -> int:
    try:
        return int(os.environ.get("MIN_DEPENDENCY_AGE_HOURS", DEFAULT_MIN_AGE_HOURS))
    except ValueError:
        return DEFAULT_MIN_AGE_HOURS


# return input as a datetime object; default to current UTC time
def now_utc(raw: str | None) -> datetime:
    if raw:
        return parse_datetime(raw)
    return datetime.now(timezone.utc)


# now_utc helper to parse input as a datetime object; used for Gradle and Maven timestamps
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


# normalize datetime to YYYY-MM-DDTHH:MM:SSZ for GitHub Actions outputs
def format_datetime(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# normalize datetime to YYYY-MM-DD date for more readable PR comment outputs
def format_date(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%d")


# emit key=value lines to stdout and GitHub Actions output file
def emit_outputs(outputs: dict[str, Any], github_output: str | None) -> None:
    lines = [f"{key}={'' if value is None else value}" for key, value in outputs.items()]
    for line in lines:
        print(line)
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            for line in lines:
                handle.write(f"{line}\n")


# load JSON from file or URL
def load_json(file_path: str | None, url: str | None) -> Any:
    if file_path:
        text = Path(file_path).read_text(encoding="utf-8")
        text = re.sub(r"(?<!:)//[^\n]*", "", text)  # strip // line comments, preserve ://
        return json.loads(text)
    if not url:
        raise ValueError("either file_path or url is required")
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.load(response)


# select latest Gradle release that is at least MIN_DEPENDENCY_AGE_HOURS hours old
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
        if entry.get("rcFor") or entry.get("milestoneFor"):
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
        current_version=args.current_version,
    )


# select latest Maven artifact release that is at least MIN_DEPENDENCY_AGE_HOURS hours old
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
        current_version=args.current_version,
    )


# combine prerelease patterns into a single regex pattern
def combine_patterns(patterns: list[str]) -> re.Pattern[str] | None:
    non_empty = [pattern for pattern in patterns if pattern]
    if not non_empty:
        return None
    return re.compile("|".join(f"(?:{pattern})" for pattern in non_empty), re.IGNORECASE)


# load all Maven Central versions for given group:artifact
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


# parse a version string into a tuple of ints for numeric comparison (e.g. "3.9.11" → (3, 9, 11))
def _version_sort_key(version: str) -> tuple:
    parts = []
    for segment in re.split(r"([.\-])", version):
        if segment in {"", ".", "-"}:
            continue
        try:
            parts.append((0, int(segment)))
        except ValueError:
            parts.append((1, segment))
    return tuple(parts)


# emit selection result to stdout and GitHub Actions output file for select-gradle and select-maven
def emit_selection_result(
    *,
    label: str,
    cutoff: datetime,
    github_output: str | None,
    candidates: list[Candidate],
    not_found_reason: str,
    current_version: str | None = None,
) -> int:
    selected = max(candidates, key=lambda candidate: _version_sort_key(candidate.version), default=None)
    outputs: dict[str, Any] = {}

    # If the current version is already >= the best candidate, keep it
    if current_version and (
        not selected
        or _version_sort_key(current_version) >= _version_sort_key(selected.version)
    ):
        outputs.update(
            {
                "found": "true",
                "version": current_version,
                "published_at": "",
                "reason": "",
            }
        )
        emit_outputs(outputs, github_output)
        if selected:
            print(
                f"Current version {current_version} for {label} is already >= "
                f"latest eligible {selected.version}; keeping current version."
            )
        else:
            print(
                f"No eligible version found for {label}; "
                f"keeping current version {current_version}."
            )
        return 0

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
            "published_at": format_date(selected.published_at),
            "reason": "",
        }
    )
    emit_outputs(outputs, github_output)
    print(
        f"Selected latest eligible stable version for {label}: "
        f"{selected.version} (published {format_date(selected.published_at)})"
    )
    return 0


def main() -> int:
    args = parse_args()
    if args.command == "select-gradle":
        return select_gradle_release(args)
    if args.command == "select-maven":
        return select_maven_release(args)
    raise ValueError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    sys.exit(main())
