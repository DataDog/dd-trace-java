from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_codeowners import path_matches, redundant_entries, segment_matches


class CodeownersTest(unittest.TestCase):
    def check(self, contents, files=("src/Test.java",)):
        return redundant_entries(contents, set(files))

    def test_redundant_file(self):
        self.assertEqual(
            self.check("/src/ @team\n/src/Test.java @team\n"),
            [(2, 1, "/src/Test.java")],
        )

    def test_pr_13154(self):
        path = "dd-java-agent/instrumentation/play/play-2.6/src/testFixtures/java/playtest/ControlledAction.java"
        self.assertEqual(self.check(
            "/dd-java-agent/instrumentation/ @DataDog/apm-java @DataDog/apm-idm-java\n"
            f"/{path} @DataDog/apm-java @DataDog/apm-idm-java\n", [path]),
            [(2, 1, "/" + path)])

    def test_owners_are_a_set_and_comments_preserve_line_numbers(self):
        self.assertEqual(self.check(
            "# heading\n\n/src/ @a @b # owners\n/src/Test.java @b @a\n"),
            [(4, 3, "/src/Test.java")])

    def test_different_owners_are_needed(self):
        self.assertEqual(self.check("/src/ @a\n/src/Test.java @b\n"), [])

    def test_reset_after_intervening_override_is_needed(self):
        self.assertEqual(self.check(
            "/src/ @a\n**/*.java @b\n/src/Test.java @a\n"), [])

    def test_later_override_does_not_hide_redundancy(self):
        self.assertEqual(self.check(
            "/src/ @a\n/src/Test.java @a\n**/*.java @b\n"),
            [(2, 1, "/src/Test.java")])

    def test_no_previous_match(self):
        self.assertEqual(self.check("/src/Test.java @a\n"), [])

    def test_empty_owners_clear_ownership(self):
        self.assertEqual(self.check("/src/ @a\n/src/Test.java\n"), [])
        self.assertEqual(self.check("/src/\n/src/Test.java\n"),
                         [(2, 1, "/src/Test.java")])

    def test_wildcard_and_directory_candidates_are_not_checked(self):
        self.assertEqual(self.check(
            "/src/ @a\n/src/* @a\n/src/nested/ @a\n"), [])

    def test_only_tracked_files_are_candidates(self):
        self.assertEqual(self.check("/src/ @a\n/src/Missing.java @a\n"), [])

    def test_double_star_and_single_star_semantics(self):
        self.assertEqual(self.check(
            "/src/*/Test.java @a\n/src/a/b/Test.java @a\n",
            ["src/a/b/Test.java"]), [])
        self.assertEqual(self.check(
            "/src/**/Test.java @a\n/src/a/b/Test.java @a\n",
            ["src/a/b/Test.java"]), [(2, 1, "/src/a/b/Test.java")])

    def test_case_sensitive(self):
        self.assertEqual(self.check("/SRC/ @a\n/src/Test.java @a\n"), [])

    def test_invalid_codeowners_patterns_are_not_gitignore_rules(self):
        self.assertEqual(self.check(
            "/src/ @a\n!src/Test.java\n/src/[T]est.java @b\n/src/Test.java @a\n"),
            [(4, 1, "/src/Test.java")])

    def test_terminal_star_does_not_cover_descendants(self):
        self.assertEqual(self.check("/src/* @a\n/src/a/Test.java @a\n",
                                    ["src/a/Test.java"]), [])

    def test_basename_pattern_matches_at_any_depth(self):
        self.assertEqual(self.check("*.java @a\n/src/Test.java @a\n"),
                         [(2, 1, "/src/Test.java")])

    def test_question_mark(self):
        self.assertEqual(self.check("/src/T?st.java @a\n/src/Test.java @a\n"),
                         [(2, 1, "/src/Test.java")])

    def test_directory_match_requires_segment_boundary(self):
        self.assertEqual(self.check("/sr/ @a\n/src/Test.java @a\n"), [])

    def test_unanchored_directory_matches_at_any_depth(self):
        self.assertEqual(self.check("src/ @a\n/project/src/Test.java @a\n",
                                    ["project/src/Test.java"]),
                         [(2, 1, "/project/src/Test.java")])

    def test_pathological_wildcards_do_not_backtrack(self):
        self.assertFalse(segment_matches("*a" * 150 + "b", "a" * 150))
        self.assertFalse(path_matches("/" + "**/" * 100 + "b", "a/" * 100 + "c"))

    def test_regex_metacharacters_are_literal(self):
        self.assertTrue(segment_matches("Test.(a|b)+$.java", "Test.(a|b)+$.java"))
        self.assertFalse(segment_matches("Test.(a|b)+$.java", "Test.aaa.java"))

    def test_cli_exit_status_and_diagnostic(self):
        with tempfile.TemporaryDirectory(prefix="codeowners-test-") as directory:
            root = Path(directory)
            (root / "tooling").mkdir()
            shutil.copy(Path(__file__).resolve().parents[1] / "check_codeowners.py",
                        root / "tooling/check_codeowners.py")
            (root / ".github").mkdir()
            (root / "src").mkdir()
            (root / "src/Test.java").touch()
            subprocess.run(["git", "init", "--quiet", "--template=", directory], check=True)
            subprocess.run(["git", "add", "src/Test.java"], cwd=root, check=True)
            codeowners = root / ".github/CODEOWNERS"
            codeowners.write_text("/src/ @team\n/src/Test.java @team\n", encoding="utf-8")
            command = [sys.executable, str(root / "tooling/check_codeowners.py")]
            failed = subprocess.run(command, cwd=root, capture_output=True, text=True)
            self.assertEqual(failed.returncode, 1, failed.stderr)
            self.assertIn("CODEOWNERS:2: redundant entry /src/Test.java", failed.stderr)
            self.assertIn("inherited from line 1", failed.stderr)
            codeowners.write_text("/src/ @team\n", encoding="utf-8")
            passed = subprocess.run(command, cwd=root, capture_output=True, text=True)
            self.assertEqual(passed.returncode, 0, passed.stderr)


if __name__ == "__main__":
    unittest.main()
