import json
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
    r"^(cutoff_at|found|version|published_at|reason|validated_coordinates|reverted_coordinates)=(.*)$"
)


class DependencyAgeScriptTest(unittest.TestCase):
    def run_script(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(SCRIPT), *args],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
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
        Runs validate-lockfiles with in-memory lockfile content.

        baseline / current map relative paths (e.g. "module/gradle.lockfile")
        to the text content of that file.  Returns the completed process and
        the temp dir root so callers can read back modified files.
        """
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        baseline_dir = Path(tmp) / "before"
        current_dir = Path(tmp) / "after"
        metadata_file = Path(tmp) / "metadata.json"

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
            "--now", now,
        )
        return result, current_dir

    def test_validates_changed_lockfiles_when_all_updates_are_old_enough(self) -> None:
        lockfile = "module/gradle.lockfile"
        baseline_content = "\n".join([
            "# Gradle lockfile",
            "com.example:lib-a:1.0.0=runtimeClasspath",
            "com.example:lib-b:1.0.0=runtimeClasspath",
            "",
        ])
        current_content = "\n".join([
            "# Gradle lockfile",
            "com.example:lib-a:1.1.0=runtimeClasspath",  # valid upgrade
            "com.example:lib-b:1.1.0=runtimeClasspath",  # valid upgrade
            "",
        ])
        metadata = {
            "com.example:lib-a:1.1.0": "2026-04-20T12:00:00Z",  # old enough
            "com.example:lib-b:1.1.0": "2026-04-20T11:00:00Z",  # old enough
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.parse_outputs(result.stdout)
        self.assertEqual(outputs["validated_coordinates"], "2")
        self.assertEqual(outputs["reverted_coordinates"], "0")
        final = (current_dir / lockfile).read_text(encoding="utf-8")
        self.assertEqual(final, current_content)

    def test_reverts_too_new_version_upgrade_keeps_valid_changes(self) -> None:
        lockfile = "module/gradle.lockfile"
        baseline_content = "\n".join([
            "# Gradle lockfile",
            "com.example:lib-a:1.0.0=runtimeClasspath",
            "com.example:lib-b:1.0.0=runtimeClasspath",
            "",
        ])
        current_content = "\n".join([
            "# Gradle lockfile",
            "com.example:lib-a:1.1.0=runtimeClasspath",  # valid upgrade
            "com.example:lib-b:2.0.0=runtimeClasspath",  # too new
            "",
        ])
        metadata = {
            "com.example:lib-a:1.1.0": "2026-04-20T12:00:00Z",  # old enough
            "com.example:lib-b:2.0.0": "2026-04-24T11:00:00Z",  # too new (after cutoff 2026-04-22T12:00:00Z)
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        final = (current_dir / lockfile).read_text(encoding="utf-8")
        self.assertIn("com.example:lib-a:1.1.0", final)   # valid upgrade kept
        self.assertIn("com.example:lib-b:1.0.0", final)   # reverted to baseline version
        self.assertNotIn("com.example:lib-b:2.0.0", final)

    def test_reverts_correct_version_when_multiple_versions_coexist(self) -> None:
        # Simulates a lockfile where the same group:artifact is locked at two different
        # versions for different configurations (e.g. compile vs runtime classpath).
        # Only one of the two versions is upgraded; the other must be left untouched.
        lockfile = "module/gradle.lockfile"
        baseline_content = "\n".join([
            "# Gradle lockfile",
            "com.typesafe:config:1.3.1=compileClasspath,testCompileClasspath",
            "com.typesafe:config:1.4.4=runtimeClasspath,testRuntimeClasspath",
            "",
        ])
        current_content = "\n".join([
            "# Gradle lockfile",
            "com.typesafe:config:1.3.1=compileClasspath,testCompileClasspath",  # unchanged
            "com.typesafe:config:1.5.0=runtimeClasspath,testRuntimeClasspath",  # too new
            "",
        ])
        metadata = {
            "com.typesafe:config:1.5.0": "2026-04-24T11:00:00Z",  # too new
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        final = (current_dir / lockfile).read_text(encoding="utf-8")
        self.assertIn("com.typesafe:config:1.3.1=compileClasspath,testCompileClasspath", final)
        self.assertIn("com.typesafe:config:1.4.4=runtimeClasspath,testRuntimeClasspath", final)
        self.assertNotIn("com.typesafe:config:1.5.0", final)

    def test_reverts_each_version_independently_for_same_group_artifact(self) -> None:
        # Both versions of the same group:artifact are upgraded to too-new versions.
        # Each must be reverted to its own predecessor, not both to the same line.
        lockfile = "module/gradle.lockfile"
        baseline_content = "\n".join([
            "# Gradle lockfile",
            "com.example:lib:1.0.0=compileClasspath",
            "com.example:lib:2.0.0=runtimeClasspath",
            "",
        ])
        current_content = "\n".join([
            "# Gradle lockfile",
            "com.example:lib:1.1.0=compileClasspath",  # too new, should revert to 1.0.0
            "com.example:lib:2.1.0=runtimeClasspath",  # too new, should revert to 2.0.0
            "",
        ])
        metadata = {
            "com.example:lib:1.1.0": "2026-04-24T11:00:00Z",  # too new
            "com.example:lib:2.1.0": "2026-04-24T11:00:00Z",  # too new
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        final = (current_dir / lockfile).read_text(encoding="utf-8")
        self.assertIn("com.example:lib:1.0.0=compileClasspath", final)
        self.assertIn("com.example:lib:2.0.0=runtimeClasspath", final)
        self.assertNotIn("com.example:lib:1.1.0", final)
        self.assertNotIn("com.example:lib:2.1.0", final)
        # Exactly two entries — no duplication
        self.assertEqual(final.count("com.example:lib:"), 2)

    def test_removes_brand_new_dependency_that_is_too_new(self) -> None:
        lockfile = "module/gradle.lockfile"
        baseline_content = "\n".join([
            "# Gradle lockfile",
            "com.example:existing:1.0.0=runtimeClasspath",
            "",
        ])
        current_content = "\n".join([
            "# Gradle lockfile",
            "com.example:existing:1.0.0=runtimeClasspath",
            "com.example:brand-new:1.0.0=runtimeClasspath",  # new transitive dep, too new
            "",
        ])
        metadata = {
            "com.example:brand-new:1.0.0": "2026-04-24T11:00:00Z",  # too new
        }

        result, current_dir = self.run_validate_lockfiles(
            baseline={"module/gradle.lockfile": baseline_content},
            current={"module/gradle.lockfile": current_content},
            metadata=metadata,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        final = (current_dir / lockfile).read_text(encoding="utf-8")
        self.assertIn("com.example:existing:1.0.0", final)
        self.assertNotIn("com.example:brand-new", final)


if __name__ == "__main__":
    unittest.main()
