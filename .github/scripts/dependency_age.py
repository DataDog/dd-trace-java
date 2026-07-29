#!/usr/bin/env python3

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable


GRADLE_VERSIONS_URL = "https://services.gradle.org/versions/all"
MAVEN_SEARCH_URL = "https://search.maven.org/solrsearch/select"
MAVEN_REPO_URL = "https://repo1.maven.org/maven2"
DEFAULT_MIN_AGE_HOURS = 48
# Oldest Gradle major release we track a latest-patch for. The legacy Gradle instrumentation
# targets Gradle 3.0+ (the `gradle-3.0` module), so older majors are never exercised.
OLDEST_TRACKED_GRADLE_MAJOR = 3


@dataclass(frozen=True)
class Candidate:
    version: str
    published_at: datetime


# Entry point for GitHub Actions workflows
# select-gradle: get newest Gradle release that is at least MIN_DEPENDENCY_AGE_HOURS hours old
# select-maven: get newest Maven artifact release that is at least MIN_DEPENDENCY_AGE_HOURS hours old
# validate-lockfiles: check that each new coordinate in the Gradle lockfiles is at least MIN_DEPENDENCY_AGE_HOURS hours old
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

    validate = subparsers.add_parser("validate-lockfiles", help="Validate age of new coordinates in Gradle lockfiles.")
    validate.add_argument("--baseline-dir", required=True)
    validate.add_argument("--current-dir", default=".")
    validate.add_argument("--metadata-file", help="JSON file mapping group:artifact:version to a timestamp override.")
    validate.add_argument("--repo-url", action="append", default=[])
    validate.add_argument("--min-age-hours", type=int, default=default_min_age_hours())
    validate.add_argument("--now")
    validate.add_argument("--github-output", default=None)

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

    # ISO 8601: normalise Z and +HHMM -> +HH:MM for fromisoformat
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
            for key, value in outputs.items():
                text = "" if value is None else str(value)
                if "\n" in text:
                    handle.write(f"{key}<<__EOF__\n{text}\n__EOF__\n")
                else:
                    handle.write(f"{key}={text}\n")


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

    status = emit_selection_result(
        label="Gradle",
        github_output=args.github_output,
        candidates=candidates,
        not_found_reason=(
            f"No eligible stable Gradle release is at least {args.min_age_hours} hours old."
        ),
        current_version=args.current_version,
    )

    # Also emit the newest eligible stable patch for every major release, as ready-to-write
    # `gradle.latest.<major>=<version>` property lines. The Gradle smoke tests use these to
    # resolve the "oldest" Gradle version dynamically (the latest patch of the major that the
    # current Gradle TestKit still supports), so the tested floor follows Gradle automatically
    # instead of being hardcoded.
    latest_by_major = {
        major: candidate
        for major, candidate in newest_stable_per_major(candidates).items()
        if major >= OLDEST_TRACKED_GRADLE_MAJOR
    }
    block = "\n".join(
        f"gradle.latest.{major}={candidate.version}"
        for major, candidate in sorted(latest_by_major.items())
    )
    emit_outputs({"latest_by_major": block}, args.github_output)
    for major, candidate in sorted(latest_by_major.items()):
        print(f"Latest eligible stable Gradle {major}.x: {candidate.version}")

    return status


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


# parse a version string into a sortable tuple for comparison; numeric segments sort before non-numeric
def _version_sort_key(version: str) -> tuple:
    segments = []
    for segment in re.split(r"([.\-])", version):
        if segment in {"", ".", "-"}:
            continue
        try:
            segments.append((0, int(segment)))
        except ValueError:
            segments.append((1, segment))

    release = []
    prerelease = []
    for i, seg in enumerate(segments):
        if seg[0] == 1:  # first string segment starts the prerelease part
            prerelease = segments[i:]
            break
        release.append(seg)

    return (tuple(release), not bool(prerelease), tuple(prerelease))


# parse the leading integer of a version string as its major release number
def _major_version(version: str) -> int:
    match = re.match(r"\s*(\d+)", version)
    if not match:
        raise ValueError(f"Cannot determine major version from '{version}'")
    return int(match.group(1))


# group candidates by major release and keep the newest one in each group
def newest_stable_per_major(candidates: list[Candidate]) -> dict[int, Candidate]:
    newest: dict[int, Candidate] = {}
    for candidate in candidates:
        major = _major_version(candidate.version)
        current = newest.get(major)
        if current is None or _version_sort_key(candidate.version) > _version_sort_key(
            current.version
        ):
            newest[major] = candidate
    return newest


# emit selection result to stdout and GitHub Actions output file for select-gradle and select-maven
def emit_selection_result(
    *,
    label: str,
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


# check that every new coordinate in the Gradle lockfiles is at least min_age_hours old
def validate_lockfiles(args: argparse.Namespace) -> int:
    cutoff = now_utc(args.now) - timedelta(hours=args.min_age_hours)
    baseline_dir = Path(args.baseline_dir)
    current_dir = Path(args.current_dir)
    metadata = load_metadata_overrides(args.metadata_file)
    repo_urls = args.repo_url if args.repo_url else [MAVEN_REPO_URL]

    # Guard against a silent snapshot failure: if baseline is empty but current has lockfiles,
    # every coordinate would appear "new" and the age check would be meaningless
    baseline_has_lockfiles = baseline_dir.exists() and any(baseline_dir.rglob("gradle.lockfile"))
    current_has_lockfiles = any(current_dir.rglob("gradle.lockfile"))
    if not baseline_has_lockfiles and current_has_lockfiles:
        print("::error::Baseline has no lockfiles but current directory does — the snapshot step may have failed.")
        emit_outputs({"cutoff_at": format_datetime(cutoff), "reverted_files": 0}, args.github_output)
        return 1

    changed = changed_lockfile_coordinates(baseline_dir=baseline_dir, current_dir=current_dir)
    if not changed:
        print("No dependency version changes detected across Gradle lockfiles.")
        emit_outputs({"cutoff_at": format_datetime(cutoff), "reverted_files": 0}, args.github_output)
        return 0

    baseline_lockfiles = collect_lockfiles(baseline_dir)

    changed_by_file: dict[str, list[str]] = {}
    for relative_path, gav in changed:
        changed_by_file.setdefault(relative_path, []).append(gav)

    timestamp_cache: dict[str, tuple[datetime | None, str | None]] = {}
    # replacement value: (new_gav, hours_remaining)
    replacements_by_file: dict[str, dict[str, tuple[str, int]]] = {}
    # violation value: (gav, kind, hours_remaining or 0)
    violations_by_file: dict[str, list[tuple[str, str, int]]] = {}
    for relative_path, gavs in sorted(changed_by_file.items()):
        baseline_coords = baseline_lockfiles.get(relative_path, set())
        for gav in gavs:
            if gav not in timestamp_cache:
                timestamp_cache[gav] = resolve_gav_timestamp(gav=gav, metadata=metadata, repo_urls=repo_urls)
            published_at, reason = timestamp_cache[gav]
            if published_at is None:
                violations_by_file.setdefault(relative_path, []).append((gav, "unverified", 0))
            elif published_at > cutoff:
                hours_remaining = int((published_at - cutoff).total_seconds() / 3600) + 1
                group_id, artifact_id, version = gav.split(":", 2)
                baseline_version = highest_baseline_version(baseline_coords, group_id, artifact_id)
                eligible = find_eligible_version(
                    group_id=group_id, artifact_id=artifact_id,
                    too_new_version=version, baseline_version=baseline_version,
                    cutoff=cutoff, repo_urls=repo_urls,
                )
                if eligible:
                    replacement_gav = f"{group_id}:{artifact_id}:{eligible[0]}"
                    replacements_by_file.setdefault(relative_path, {})[gav] = (replacement_gav, hours_remaining)
                    print(f"Latest version {gav} did not meet 48h cooldown requirement, updating to {replacement_gav} instead.")
                else:
                    violations_by_file.setdefault(relative_path, []).append((gav, "too_new", hours_remaining))
            else:
                print(f"Verified {gav} (published {format_datetime(published_at)}, cutoff {format_datetime(cutoff)})")

    if replacements_by_file:
        # build the gav->gav map for apply_lockfile_replacements, skipping no-op downgrades
        effective_replacements: dict[str, dict[str, str]] = {}
        for relative_path, replacements in replacements_by_file.items():
            baseline_coords = baseline_lockfiles.get(relative_path, set())
            for old_gav, (new_gav, _) in replacements.items():
                if new_gav not in baseline_coords:
                    effective_replacements.setdefault(relative_path, {})[old_gav] = new_gav
        if effective_replacements:
            apply_lockfile_replacements(replacements_by_file=effective_replacements, current_dir=current_dir)

    if violations_by_file:
        revert_lockfiles_to_baseline(lockfile_paths=list(violations_by_file.keys()), baseline_dir=baseline_dir, current_dir=current_dir)
        for relative_path, entries in sorted(violations_by_file.items()):
            for gav, kind, _ in entries:
                print(f"::warning file={relative_path}::{gav}: {'Cannot verify age' if kind == 'unverified' else 'Too new'}. Reverted lockfile to baseline.")

    reverted_files = len(violations_by_file)
    summary_instrumentation = build_validation_summary(violations_by_file=violations_by_file, replacements_by_file=replacements_by_file, baseline_lockfiles=baseline_lockfiles, min_age_hours=args.min_age_hours, path_filter=is_instrumentation_path)
    summary_core = build_validation_summary(violations_by_file=violations_by_file, replacements_by_file=replacements_by_file, baseline_lockfiles=baseline_lockfiles, min_age_hours=args.min_age_hours, path_filter=lambda path: not is_instrumentation_path(path))
    emit_outputs(
        {
            "cutoff_at": format_datetime(cutoff),
            "reverted_files": reverted_files,
            "summary_core": summary_core,
            "summary_instrumentation": summary_instrumentation,
        },
        args.github_output,
    )
    print(f"Validated {len(changed)} changed coordinate(s) across {len(changed_by_file)} lockfile(s). {reverted_files} lockfile(s) reverted.")
    return 0


# instrumentation lockfiles live under these prefixes and ship in a separate PR from core modules.
# Keep in sync with the file split in .github/workflows/update-gradle-dependencies.yaml
INSTRUMENTATION_PATH_PREFIXES = ("dd-smoke-tests/", "dd-java-agent/instrumentation/")


# classify a lockfile path as belonging to the instrumentation PR (vs the core modules PR)
def is_instrumentation_path(relative_path: str) -> bool:
    normalized = relative_path.replace(os.sep, "/")
    return normalized.startswith(INSTRUMENTATION_PATH_PREFIXES)


# build summary of reverted/downgraded dependencies for PR descriptions
# path_filter restricts the summary to lockfiles whose relative path matches,
# so each PR (core vs instrumentation) only lists the dependencies it actually changes
#
# The summary is split into three on-screen sections, ordered by how urgently a human
# must act on them:
#   1. "Cannot verify age, reverted" — age could not be checked at all, needs manual resolution
#   2. "<N>h cooldown, reverted" — too new and no older eligible version exists, so reverted to baseline
#   3. "<N>h cooldown, updated to the previous version" — too new, downgraded to an older eligible version
def build_validation_summary(
    *,
    violations_by_file: dict[str, list[tuple[str, str, int]]],
    replacements_by_file: dict[str, dict[str, tuple[str, int]]],
    baseline_lockfiles: dict[str, set[str]],
    min_age_hours: int,
    path_filter: Callable[[str], bool],
) -> str:
    unverified: list[str] = []
    cooldown_reverted: list[str] = []
    cooldown_updated: list[str] = []
    seen: set[str] = set()

    for relative_path, replacements in replacements_by_file.items():
        if not path_filter(relative_path):
            continue
        baseline_coords = baseline_lockfiles.get(relative_path, set())
        for old_gav, (new_gav, hours_remaining) in replacements.items():
            if old_gav in seen:
                continue
            seen.add(old_gav)
            if new_gav in baseline_coords:
                # the only eligible version was the baseline, so this is effectively a revert
                cooldown_reverted.append(f"- `{old_gav}` is {hours_remaining}h away from meeting cooldown")
            else:
                new_version = new_gav.rsplit(":", 1)[1]
                cooldown_updated.append(f"- `{old_gav}` is {hours_remaining}h away from meeting cooldown, updated to `{new_version}`")
    for relative_path, entries in violations_by_file.items():
        if not path_filter(relative_path):
            continue
        for gav, kind, hours_remaining in entries:
            if gav in seen:
                continue
            seen.add(gav)
            if kind == "unverified":
                unverified.append(f"- `{gav}`")
            else:
                cooldown_reverted.append(f"- `{gav}` is {hours_remaining}h away from meeting cooldown")

    blocks: list[str] = []
    if unverified:
        blocks.append(
            "### :warning: Cannot verify age, reverted\n\n"
            "The age of these dependencies could not be verified, so the lockfiles were reverted. "
            "This likely means that the following dependencies are published to a repo not yet configured in the workflow. "
            "If this is the case, add the missing repository as a `--repo-url` in the `Validate changed lock files` step of `.github/workflows/update-gradle-dependencies.yaml`. "
            "**This needs to be resolved manually.**\n\n"
            + "\n".join(sorted(unverified))
        )
    if cooldown_reverted:
        blocks.append(
            f"### {min_age_hours}h cooldown, reverted\n\n"
            "Too new and no older eligible version exists, so the lockfiles were reverted to the baseline.\n\n"
            + "\n".join(sorted(cooldown_reverted))
        )
    if cooldown_updated:
        blocks.append(
            f"### {min_age_hours}h cooldown, updated to the previous version\n\n"
            "Too new, so an older eligible version was used instead.\n\n"
            + "\n".join(sorted(cooldown_updated))
        )
    if not blocks:  # nothing matched the filter
        return ""
    return "## Dependency age policy\n\n" + "\n\n".join(blocks)


# replace specific coordinates in lockfiles (for version downgrades)
def apply_lockfile_replacements(
    *,
    replacements_by_file: dict[str, dict[str, str]],
    current_dir: Path,
) -> None:
    for relative_path, replacements in sorted(replacements_by_file.items()):
        lockfile_path = current_dir / relative_path
        lines = lockfile_path.read_text(encoding="utf-8").splitlines(keepends=True)
        new_lines = []
        for line in lines:
            stripped = line.strip()
            if stripped and not stripped.startswith("#"):
                coordinate = stripped.split("=", 1)[0]
                if coordinate in replacements:
                    configs = stripped.split("=", 1)[1] if "=" in stripped else ""
                    new_coord = replacements[coordinate]
                    line = f"{new_coord}={configs}\n"
            new_lines.append(line)
        lockfile_path.write_text("".join(new_lines), encoding="utf-8")


# restore each violating lockfile to its baseline copy to keep the file consistent
def revert_lockfiles_to_baseline(
    *,
    lockfile_paths: list[str],
    baseline_dir: Path,
    current_dir: Path,
) -> None:
    for relative_path in sorted(lockfile_paths):
        current_path = current_dir / relative_path
        baseline_path = baseline_dir / relative_path
        if baseline_path.exists():
            current_path.write_text(baseline_path.read_text(encoding="utf-8"), encoding="utf-8")
            print(f"Reverted {relative_path} to baseline.")
        else:
            current_path.unlink(missing_ok=True)
            print(f"Removed new lockfile {relative_path} (no baseline copy to restore).")


# look up the publish timestamp for a group:artifact:version coordinate
# uses a HEAD request against the POM file to read the Last-Modified header
# tries each repo URL in order, falling back to the next on 404
# returns (datetime, None) on success; (None, reason) when the timestamp cannot be determined
def resolve_gav_timestamp(
    *,
    gav: str,
    metadata: dict[str, Any],
    repo_urls: list[str],
) -> tuple[datetime | None, str | None]:
    if gav in metadata:
        return parse_metadata_override(gav, metadata[gav])

    group_id, artifact_id, version = gav.split(":", 2)
    group_path = group_id.replace(".", "/")
    pom_path = f"{group_path}/{artifact_id}/{version}/{artifact_id}-{version}.pom"

    for repo_url in repo_urls:
        result = _head_pom_timestamp(f"{repo_url}/{pom_path}")
        if result is not None:
            return result, None
    return None, f"{gav} was not found in any configured repository."


# issue a HEAD request for a POM URL and return the parsed Last-Modified timestamp, or None on 404
# retries once on transient errors; raises on persistent non-404 failures
def _head_pom_timestamp(pom_url: str) -> datetime | None:
    for attempt in range(2):
        try:
            request = urllib.request.Request(pom_url, method="HEAD")
            with urllib.request.urlopen(request, timeout=30) as response:
                last_modified = response.headers.get("Last-Modified")
            if not last_modified:
                return None
            return parsedate_to_datetime(last_modified).astimezone(timezone.utc)
        except urllib.error.HTTPError as exc:
            if exc.code in (404, 403):
                return None
            if attempt == 1:
                return None
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 1:
                return None
    return None


# fetch the list of available versions for a group:artifact from maven-metadata.xml
# tries each repo URL in order; returns versions sorted newest-first using _version_sort_key
def fetch_available_versions(group_id: str, artifact_id: str, repo_urls: list[str]) -> list[str]:
    group_path = group_id.replace(".", "/")
    metadata_path = f"{group_path}/{artifact_id}/maven-metadata.xml"
    for repo_url in repo_urls:
        url = f"{repo_url}/{metadata_path}"
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                tree = ET.parse(response)
            versions = [v.text for v in tree.findall(".//version") if v.text]
            if versions:
                versions.sort(key=_version_sort_key, reverse=True)
                return versions
        except (urllib.error.URLError, ET.ParseError, TimeoutError, OSError):
            continue
    return []


# select the highest baseline version of group:artifact present in a lockfile.
def highest_baseline_version(baseline_coords: set[str], group_id: str, artifact_id: str) -> str | None:
    prefix = f"{group_id}:{artifact_id}:"
    versions = [coord[len(prefix):] for coord in baseline_coords if coord.startswith(prefix)]
    if not versions:
        return None
    return max(versions, key=_version_sort_key)


# for a too-new coordinate, walk backward through available versions to find the newest one
# that meets the age cutoff and is newer than the baseline version
def find_eligible_version(
    *,
    group_id: str,
    artifact_id: str,
    too_new_version: str,
    baseline_version: str | None,
    cutoff: datetime,
    repo_urls: list[str],
) -> tuple[str, datetime] | None:
    versions = fetch_available_versions(group_id, artifact_id, repo_urls)
    too_new_key = _version_sort_key(too_new_version)
    too_new_is_ga = too_new_key[1]  # True if no prerelease segments
    baseline_key = _version_sort_key(baseline_version) if baseline_version else None
    group_path = group_id.replace(".", "/")

    for version in versions:
        key = _version_sort_key(version)
        if key >= too_new_key:
            continue # skip the too-new version and anything newer
        if baseline_key is not None and key <= baseline_key:
            break # no point checking versions older than or equal to baseline
        if too_new_is_ga and not key[1]:
            continue # don't downgrade a GA release to a pre-release
        pom_path = f"{group_path}/{artifact_id}/{version}/{artifact_id}-{version}.pom"
        for repo_url in repo_urls:
            published_at = _head_pom_timestamp(f"{repo_url}/{pom_path}")
            if published_at is not None:
                if published_at <= cutoff:
                    return version, published_at
                break # version found but too new, try the next older one
    return None


# load optional metadata overrides from a JSON file (group:artifact:version -> timestamp)
def load_metadata_overrides(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    return load_json(path, None)


# parse a single metadata override value: a timestamp string/number, or a dict with a timestamp key
def parse_metadata_override(gav: str, override: Any) -> tuple[datetime | None, str | None]:
    if isinstance(override, dict):
        for key in ("timestamp", "published_at", "timestamp_ms"):
            if key in override:
                try:
                    return parse_datetime(override[key]), None
                except (ValueError, TypeError) as exc:
                    return None, f"Metadata override for {gav} has an invalid timestamp: {exc}"
        return None, f"Metadata override for {gav} is missing a timestamp key (expected: timestamp, published_at, or timestamp_ms)."
    if isinstance(override, (int, float, str)):
        try:
            return parse_datetime(override), None
        except (ValueError, TypeError) as exc:
            return None, f"Metadata override for {gav} has an invalid timestamp: {exc}"
    return None, f"Unsupported metadata override format for {gav}."


# diff baseline and current lockfile directories; return (relative_path, gav) for each added or changed coordinate
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


# recursively find all gradle.lockfile paths under root and parse them into sets of coordinates
def collect_lockfiles(root: Path) -> dict[str, set[str]]:
    if not root.exists():
        return {}
    return {
        str(path.relative_to(root)): parse_lockfile(path)
        for path in root.rglob("gradle.lockfile")
    }


# parse a lockfile into a set of group:artifact:version coordinates (skipping comments and empty lines)
def parse_lockfile(path: Path) -> set[str]:
    coordinates: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        coordinate = line.split("=", 1)[0]
        if coordinate.count(":") == 2:
            coordinates.add(coordinate)
    return coordinates


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
