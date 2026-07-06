import argparse
import contextlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".github/scripts/dependency_age.py"

# dependency_age.py is a loose script (not a package); add its dir to sys.path
# so its helpers can be imported and unit-tested.
sys.path.insert(0, str(SCRIPT.parent))
import dependency_age


FIXTURES = Path(__file__).resolve().parent / "fixtures"
NOW = "2026-04-24T12:00:00Z"
OUTPUT_PATTERN = re.compile(
    r"^(cutoff_at|found|version|published_at|reason|reverted_files)=(.*)$"
)


class DependencyAgeScriptTest(unittest.TestCase):
    def run_script(self, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        process_env = os.environ.copy()
        if env:
            process_env.update(env)
        return subprocess.run(
            ["python3", str(SCRIPT), *args],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
            env=process_env,
        )

    def parse_outputs(self, stdout: str) -> dict[str, str]:
        outputs: dict[str, str] = {}
        for line in stdout.splitlines():
            match = OUTPUT_PATTERN.match(line)
            if match:
                outputs[match.group(1)] = match.group(2)
        return outputs

    def test_selects_previous_gradle_release_when_newest_is_too_new(self) -> None:
        result = self.run_script(
            "select-gradle",
            "--now",
            NOW,
            "--versions-file",
            str(FIXTURES / "gradle-newest-too-new.json"),
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["version"], "9.4.1")
        self.assertEqual(outputs["published_at"], "2026-04-22")

    def test_reports_when_no_eligible_gradle_release_exists(self) -> None:
        result = self.run_script(
            "select-gradle",
            "--now",
            NOW,
            "--versions-file",
            str(FIXTURES / "gradle-no-eligible.json"),
        )

        self.assertEqual(result.returncode, 1, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["found"], "false")
        self.assertIn("No eligible stable Gradle release", outputs["reason"])

    def test_selects_previous_maven_release_when_newest_is_too_new(self) -> None:
        result = self.run_script(
            "select-maven",
            "--now",
            NOW,
            "--group-id",
            "org.apache.maven",
            "--artifact-id",
            "apache-maven",
            "--search-response-file",
            str(FIXTURES / "maven-newest-too-new.json"),
            "--prerelease-pattern",
            "alpha",
            "--prerelease-pattern",
            "beta",
            "--prerelease-pattern",
            "rc",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["version"], "3.9.8")

    def test_filters_rc_and_milestone_releases_by_json_fields(self) -> None:
        result = self.run_script(
            "select-gradle",
            "--now",
            NOW,
            "--versions-file",
            str(FIXTURES / "gradle-prerelease-filtering.json"),
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["version"], "9.4.1")

    def test_exact_48_hour_boundary_is_accepted(self) -> None:
        result = self.run_script(
            "select-maven",
            "--now",
            NOW,
            "--group-id",
            "org.apache.maven.plugins",
            "--artifact-id",
            "maven-surefire-plugin",
            "--search-response-file",
            str(FIXTURES / "surefire-boundary.json"),
            "--prerelease-pattern",
            "alpha",
            "--prerelease-pattern",
            "beta",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["version"], "3.5.5")
        self.assertEqual(outputs["published_at"], "2026-04-22")


    def test_ga_version_overrides_current_prerelease(self) -> None:
        result = self.run_script(
            "select-maven",
            "--now",
            NOW,
            "--group-id",
            "org.apache.maven",
            "--artifact-id",
            "apache-maven",
            "--search-response-file",
            str(FIXTURES / "maven-ga-replaces-beta.json"),
            "--prerelease-pattern",
            "alpha",
            "--prerelease-pattern",
            "beta",
            "--prerelease-pattern",
            "rc",
            "--current-version",
            "4.0.0-beta-3",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["found"], "true")
        self.assertEqual(outputs["version"], "4.0.0")
        self.assertEqual(outputs["published_at"], "2026-04-20")

    def test_keeps_current_version_when_higher_than_eligible(self) -> None:
        result = self.run_script(
            "select-maven",
            "--now",
            NOW,
            "--group-id",
            "org.apache.maven",
            "--artifact-id",
            "apache-maven",
            "--search-response-file",
            str(FIXTURES / "maven-newest-too-new.json"),
            "--prerelease-pattern",
            "alpha",
            "--prerelease-pattern",
            "beta",
            "--prerelease-pattern",
            "rc",
            "--current-version",
            "4.0.0-beta-3",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["found"], "true")
        self.assertEqual(outputs["version"], "4.0.0-beta-3")
        self.assertEqual(outputs["published_at"], "")

    def test_updates_when_eligible_version_is_higher_than_current(self) -> None:
        result = self.run_script(
            "select-maven",
            "--now",
            NOW,
            "--group-id",
            "org.apache.maven.plugins",
            "--artifact-id",
            "maven-surefire-plugin",
            "--search-response-file",
            str(FIXTURES / "surefire-boundary.json"),
            "--prerelease-pattern",
            "alpha",
            "--prerelease-pattern",
            "beta",
            "--current-version",
            "3.5.4",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["found"], "true")
        self.assertEqual(outputs["version"], "3.5.5")
        self.assertEqual(outputs["published_at"], "2026-04-22")

    def test_keeps_current_version_when_no_eligible_version_exists(self) -> None:
        result = self.run_script(
            "select-gradle",
            "--now",
            NOW,
            "--versions-file",
            str(FIXTURES / "gradle-no-eligible.json"),
            "--current-version",
            "9.0.0",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["found"], "true")
        self.assertEqual(outputs["version"], "9.0.0")
        self.assertEqual(outputs["published_at"], "")


    def run_validate_lockfiles(
        self,
        *,
        baseline: dict[str, str],
        current: dict[str, str],
        metadata: dict,
        now: str = NOW,
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        """
        Run validate-lockfiles with in-memory lockfile content.
        baseline/current map relative paths to file text.
        Any uncovered coordinate hits the (unreachable) repo URL and is
        treated as a violation (fail-closed), causing the lockfile to be reverted.
        """
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        baseline_dir = tmp / "before"
        current_dir = tmp / "after"
        metadata_file = tmp / "metadata.json"

        for rel_path, content in baseline.items():
            p = baseline_dir / rel_path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")

        for rel_path, content in current.items():
            p = current_dir / rel_path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")

        metadata_file.write_text(json.dumps(metadata), encoding="utf-8")

        result = self.run_script(
            "validate-lockfiles",
            "--baseline-dir", str(baseline_dir),
            "--current-dir", str(current_dir),
            "--metadata-file", str(metadata_file),
            "--repo-url", (tmp / "no-network-repo").as_uri(),
            "--now", now,
        )
        return result, current_dir

    def test_validates_changed_lockfiles_when_all_updates_are_old_enough(self) -> None:
        baseline_content = "# lockfile\ncom.example:lib-a:1.0.0=runtimeClasspath\ncom.example:lib-b:1.0.0=runtimeClasspath\n"
        current_content  = "# lockfile\ncom.example:lib-a:1.1.0=runtimeClasspath\ncom.example:lib-b:1.1.0=runtimeClasspath\n"
        metadata = {
            "com.example:lib-a:1.1.0": "2026-04-20T12:00:00Z",
            "com.example:lib-b:1.1.0": "2026-04-20T11:00:00Z",
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["reverted_files"], "0")
        self.assertEqual((current_dir / "module/gradle.lockfile").read_text(encoding="utf-8"), current_content)

    def test_reverts_lockfile_when_any_changed_dependency_is_too_new(self) -> None:
        baseline_content = "# lockfile\ncom.example:lib-a:1.0.0=runtimeClasspath\ncom.example:lib-b:1.0.0=runtimeClasspath\n"
        current_content  = "# lockfile\ncom.example:lib-a:1.1.0=runtimeClasspath\ncom.example:lib-b:2.0.0=runtimeClasspath\n"
        metadata = {
            "com.example:lib-a:1.1.0": "2026-04-20T12:00:00Z",  # old enough
            "com.example:lib-b:2.0.0": "2026-04-24T11:00:00Z",  # too new
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["reverted_files"], "1")
        self.assertEqual((current_dir / "module/gradle.lockfile").read_text(encoding="utf-8"), baseline_content)

    def test_removes_brand_new_lockfile_with_too_new_dependency(self) -> None:
        # A brand-new module has no baseline counterpart — the lockfile should be removed.
        # So include an unchanged pre-existing lockfile in both baseline and current to satisfy
        # the precondition check that confirms the snapshot step ran successfully.
        existing_content = "# lockfile\ncom.existing:lib:1.0.0=runtimeClasspath\n"
        new_content = "# lockfile\ncom.example:brand-new:1.0.0=runtimeClasspath\n"
        metadata = {
            "com.example:brand-new:1.0.0": "2026-04-24T11:00:00Z",  # too new
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"existing/gradle.lockfile": existing_content},
            current={
                "existing/gradle.lockfile": existing_content,
                "new-module/gradle.lockfile": new_content,
            },
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((current_dir / "new-module/gradle.lockfile").exists())

    def test_reverts_lockfile_when_metadata_lookup_fails(self) -> None:
        # coordinate not in metadata -> hits unreachable search URL -> treated as violation (fail-closed)
        baseline_content = "# lockfile\ncom.example:lib:1.0.0=runtimeClasspath\n"
        current_content  = "# lockfile\ncom.example:lib:1.1.0=runtimeClasspath\n"

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata={},
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["reverted_files"], "1")
        self.assertIn("::warning", result.stdout)
        self.assertEqual((current_dir / "module/gradle.lockfile").read_text(encoding="utf-8"), baseline_content)

    def test_exits_cleanly_when_lockfiles_are_identical(self) -> None:
        # no changes between baseline and current -> exit 0 with reverted_files=0
        content = "# lockfile\ncom.example:lib:1.0.0=runtimeClasspath\n"

        result, _ = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": content},
            current={"module/gradle.lockfile": content},
            metadata={},
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["reverted_files"], "0")
        self.assertIn("No dependency version changes", result.stdout)

    def test_reverts_lockfile_when_metadata_override_has_invalid_timestamp(self) -> None:
        # malformed timestamp in metadata override -> cannot verify age -> fail-closed revert
        baseline_content = "# lockfile\ncom.example:lib:1.0.0=runtimeClasspath\n"
        current_content  = "# lockfile\ncom.example:lib:1.1.0=runtimeClasspath\n"

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata={"com.example:lib:1.1.0": "not-a-valid-date"},
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["reverted_files"], "1")
        self.assertEqual((current_dir / "module/gradle.lockfile").read_text(encoding="utf-8"), baseline_content)

    def test_is_instrumentation_path_classifies_prefixes(self) -> None:
        self.assertTrue(dependency_age.is_instrumentation_path("dd-smoke-tests/foo/gradle.lockfile"))
        self.assertTrue(dependency_age.is_instrumentation_path("dd-java-agent/instrumentation/bar/gradle.lockfile"))
        # core modules are not instrumentation
        self.assertFalse(dependency_age.is_instrumentation_path("dd-trace-core/gradle.lockfile"))
        self.assertFalse(dependency_age.is_instrumentation_path("dd-java-agent/agent-bootstrap/gradle.lockfile"))
        # real sibling modules that share the "dd-java-agent/instrumentation" stem but are
        # NOT under the "dd-java-agent/instrumentation/" prefix — the trailing slash excludes them
        self.assertFalse(dependency_age.is_instrumentation_path("dd-java-agent/instrumentation-testing/gradle.lockfile"))
        self.assertFalse(dependency_age.is_instrumentation_path("dd-java-agent/instrumentation-annotation-processor/gradle.lockfile"))

    def _summary(self, *, path_filter) -> str:
        # one too-new violation in a core module, one in an instrumentation module
        return dependency_age.build_validation_summary(
            violations_by_file={
                "dd-trace-core/gradle.lockfile": [("com.example:core-lib:2.0.0", "too_new", 5)],
                "dd-java-agent/instrumentation/foo/gradle.lockfile": [("com.example:inst-lib:3.0.0", "too_new", 7)],
            },
            replacements_by_file={},
            baseline_lockfiles={},
            min_age_hours=48,
            path_filter=path_filter,
        )

    def test_core_summary_excludes_instrumentation_entries(self) -> None:
        summary = self._summary(path_filter=lambda p: not dependency_age.is_instrumentation_path(p))
        self.assertIn("com.example:core-lib:2.0.0", summary)
        self.assertNotIn("com.example:inst-lib:3.0.0", summary)

    def test_instrumentation_summary_excludes_core_entries(self) -> None:
        summary = self._summary(path_filter=dependency_age.is_instrumentation_path)
        self.assertIn("com.example:inst-lib:3.0.0", summary)
        self.assertNotIn("com.example:core-lib:2.0.0", summary)

    def test_summary_is_empty_when_filter_matches_nothing(self) -> None:
        empty = dependency_age.build_validation_summary(
            violations_by_file={"dd-trace-core/gradle.lockfile": [("com.example:core-lib:2.0.0", "too_new", 5)]},
            replacements_by_file={},
            baseline_lockfiles={},
            min_age_hours=48,
            path_filter=dependency_age.is_instrumentation_path,  # nothing under instrumentation
        )
        self.assertEqual(empty, "")

    def test_summary_groups_outcomes_into_sections(self) -> None:
        # one of each outcome: unverified, too-new revert, replacement-as-revert, replacement-as-update
        summary = dependency_age.build_validation_summary(
            violations_by_file={
                "core/gradle.lockfile": [
                    ("com.example:unverified-lib:1.0.0", "unverified", 0),
                    ("com.example:too-new-lib:2.0.0", "too_new", 5),
                ],
            },
            replacements_by_file={
                "core/gradle.lockfile": {
                    # eligible version equals the baseline -> effectively a revert
                    "com.example:revert-lib:3.0.0": ("com.example:revert-lib:2.9.0", 7),
                    # eligible version is newer than the baseline -> an update to a previous version
                    "com.example:update-lib:4.0.0": ("com.example:update-lib:3.9.0", 9),
                },
            },
            baseline_lockfiles={
                "core/gradle.lockfile": {"com.example:revert-lib:2.9.0"},
            },
            min_age_hours=48,
            path_filter=lambda p: True,
        )

        # three section headings present, in priority order
        unverified_idx = summary.index("### :warning: Cannot verify age, reverted")
        reverted_idx = summary.index("### 48h cooldown, reverted")
        updated_idx = summary.index("### 48h cooldown, updated to the previous version")
        self.assertLess(unverified_idx, reverted_idx)
        self.assertLess(reverted_idx, updated_idx)

        # unverified entry lives under the manual-resolution section
        self.assertIn("**This needs to be resolved manually.**", summary)
        self.assertIn("- `com.example:unverified-lib:1.0.0`", summary)

        # both the too-new violation and the revert-style replacement land in the reverted section
        reverted_block = summary[reverted_idx:updated_idx]
        self.assertIn("com.example:too-new-lib:2.0.0", reverted_block)
        self.assertIn("com.example:revert-lib:3.0.0", reverted_block)

        # the update names the older version that was used instead
        updated_block = summary[updated_idx:]
        self.assertIn("com.example:update-lib:4.0.0", updated_block)
        self.assertIn("updated to `3.9.0`", updated_block)

    def test_highest_baseline_version_picks_newest_of_coexisting_pins(self) -> None:
        # A single lockfile can pin the same artifact at several versions (one per Gradle
        # configuration). The baseline should be the newest so that only versions higher than
        # the highest existing version are considered upgrades.
        baseline_coords = {
            "ch.qos.logback:logback-core:1.1.11",
            "ch.qos.logback:logback-core:1.2.13",
            "ch.qos.logback:logback-core:1.5.34",
            "com.example:unrelated:9.9.9",
        }
        self.assertEqual(
            dependency_age.highest_baseline_version(baseline_coords, "ch.qos.logback", "logback-core"),
            "1.5.34",
        )
        self.assertIsNone(
            dependency_age.highest_baseline_version(baseline_coords, "ch.qos.logback", "logback-classic")
        )

    def test_summary_omits_empty_sections(self) -> None:
        # only too-new violations -> only the "reverted" section should appear
        summary = dependency_age.build_validation_summary(
            violations_by_file={"core/gradle.lockfile": [("com.example:core-lib:2.0.0", "too_new", 5)]},
            replacements_by_file={},
            baseline_lockfiles={},
            min_age_hours=48,
            path_filter=lambda p: True,
        )
        self.assertIn("### 48h cooldown, reverted", summary)
        self.assertNotIn("Cannot verify age", summary)
        self.assertNotIn("updated to the previous version", summary)


if __name__ == "__main__":
    unittest.main()
