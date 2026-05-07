import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".github/scripts/dependency_age.py"
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
        self.assertEqual(outputs["published_at"], "2026-04-22T11:00:00Z")
        self.assertEqual(outputs["cutoff_at"], "2026-04-22T12:00:00Z")

    def test_reports_when_no_eligible_gradle_release_exists(self) -> None:
        result = self.run_script(
            "select-gradle",
            "--now",
            NOW,
            "--versions-file",
            str(FIXTURES / "gradle-no-eligible.json"),
        )

        self.assertEqual(result.returncode, 1, result.stdout)
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
        self.assertEqual(outputs["published_at"], "2026-04-22T12:00:00Z")


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
        Any uncovered coordinate hits the (unreachable) search URL and is
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
            "--search-url", (tmp / "no-network").as_uri(),
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

    def test_reverts_lockfile_when_one_of_multiple_coexisting_versions_is_too_new(self) -> None:
        baseline_content = "# lockfile\ncom.typesafe:config:1.3.1=compileClasspath\ncom.typesafe:config:1.4.4=runtimeClasspath\n"
        current_content  = "# lockfile\ncom.typesafe:config:1.3.1=compileClasspath\ncom.typesafe:config:1.5.0=runtimeClasspath\n"
        metadata = {
            "com.typesafe:config:1.5.0": "2026-04-24T11:00:00Z",  # too new
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
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

    def test_fails_when_baseline_has_no_lockfiles_but_current_does(self) -> None:
        # empty baseline with lockfiles in current suggests the snapshot step failed
        current_content = "# lockfile\ncom.example:lib:1.0.0=runtimeClasspath\n"

        result, _ = self.run_validate_lockfiles(
            baseline={},
            current={"module/gradle.lockfile": current_content},
            metadata={},
        )

        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("::error::Baseline has no lockfiles", result.stdout)

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


if __name__ == "__main__":
    unittest.main()
