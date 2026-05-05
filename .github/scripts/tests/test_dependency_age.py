import os
import re
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".github/scripts/dependency_age.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
NOW = "2026-04-24T12:00:00Z"
OUTPUT_PATTERN = re.compile(
    r"^(cutoff_at|found|version|published_at|reason)=(.*)$"
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


if __name__ == "__main__":
    unittest.main()
