import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / ".github/scripts/check_swallowed_exceptions.py"


def run_git(repo: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )


class CheckSwallowedExceptionsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        run_git(self.repo, "init", "-q")
        run_git(self.repo, "config", "user.email", "test@example.com")
        run_git(self.repo, "config", "user.name", "test")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write_and_commit(self, relative_path: str, content: str, message: str) -> str:
        path = self.repo / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        run_git(self.repo, "add", relative_path)
        run_git(self.repo, "commit", "-q", "-m", message)
        return run_git(self.repo, "rev-parse", "HEAD").stdout.strip()

    def run_script(self, merge_base: str, head: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--merge-base", merge_base, "--head", head, "--repo-root", str(self.repo)],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_flags_newly_added_empty_catch_block(self) -> None:
        base = self.write_and_commit(
            "Example.java",
            "class Example {\n  void m() {}\n}\n",
            "base",
        )
        head = self.write_and_commit(
            "Example.java",
            "class Example {\n"
            "  void m() {\n"
            "    try {\n"
            "      doWork();\n"
            "    } catch (RuntimeException e) {\n"
            "    }\n"
            "  }\n"
            "}\n",
            "add swallowed exception",
        )
        result = self.run_script(base, head)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Example.java", result.stdout)

    def test_allows_ignored_named_variable(self) -> None:
        base = self.write_and_commit("Example.java", "class Example {}\n", "base")
        head = self.write_and_commit(
            "Example.java",
            "class Example {\n"
            "  void m() {\n"
            "    try {\n"
            "      doWork();\n"
            "    } catch (RuntimeException ignored) {\n"
            "    }\n"
            "  }\n"
            "}\n",
            "add ignored catch",
        )
        result = self.run_script(base, head)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_allows_catch_with_explanatory_comment(self) -> None:
        base = self.write_and_commit("Example.java", "class Example {}\n", "base")
        head = self.write_and_commit(
            "Example.java",
            "class Example {\n"
            "  void m() {\n"
            "    try {\n"
            "      doWork();\n"
            "    } catch (RuntimeException e) {\n"
            "      // Deliberately ignored: doWork() is best-effort.\n"
            "    }\n"
            "  }\n"
            "}\n",
            "add commented catch",
        )
        result = self.run_script(base, head)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_allows_catch_that_logs(self) -> None:
        base = self.write_and_commit("Example.java", "class Example {}\n", "base")
        head = self.write_and_commit(
            "Example.java",
            "class Example {\n"
            "  void m() {\n"
            "    try {\n"
            "      doWork();\n"
            "    } catch (RuntimeException e) {\n"
            "      log.debug(\"failed\", e);\n"
            "    }\n"
            "  }\n"
            "}\n",
            "add logging catch",
        )
        result = self.run_script(base, head)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_does_not_flag_preexisting_empty_catch_untouched_by_diff(self) -> None:
        base = self.write_and_commit(
            "Example.java",
            "class Example {\n"
            "  void m() {\n"
            "    try {\n"
            "      doWork();\n"
            "    } catch (RuntimeException e) {\n"
            "    }\n"
            "  }\n"
            "\n"
            "  void other() {}\n"
            "}\n",
            "base with pre-existing violation",
        )
        head = self.write_and_commit(
            "Example.java",
            "class Example {\n"
            "  void m() {\n"
            "    try {\n"
            "      doWork();\n"
            "    } catch (RuntimeException e) {\n"
            "    }\n"
            "  }\n"
            "\n"
            "  void other() {\n"
            "    System.out.println(\"unrelated change\");\n"
            "  }\n"
            "}\n",
            "unrelated change elsewhere in file",
        )
        result = self.run_script(base, head)
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
