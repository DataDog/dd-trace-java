from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_default_locale_format as check

TEST_FILE = "internal-api/src/test/java/datadog/trace/util/SomeTest.java"
JMH_FILE = "internal-api/src/jmh/java/datadog/trace/util/SomeBenchmark.java"
MAIN_FILE = "internal-api/src/main/java/datadog/trace/util/Some.java"


class CheckDefaultLocaleFormatTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        previous = Path.cwd()
        self.addCleanup(lambda: os.chdir(previous))
        os.chdir(self.root)
        self.run_git("init", "-q")
        self.write(TEST_FILE, 'class SomeTest {\n  String old = String.format("%d", 1);\n}\n')
        self.commit("base")

    def run_git(self, *args):
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", *args], check=True, capture_output=True)

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self, message):
        self.run_git("add", "-A")
        self.run_git("commit", "-q", "-m", message)

    def problems(self):
        return check.find_problems("HEAD~1", "HEAD")

    def test_existing_default_locale_call_is_not_reported(self):
        self.write(TEST_FILE, 'class SomeTest {\n  String old = String.format("%d", 1);\n  int x;\n}\n')
        self.commit("unrelated edit")
        self.assertEqual([], self.problems())

    def test_added_call_in_test_is_reported(self):
        self.write(TEST_FILE, 'class SomeTest {\n  String old = String.format("%d", 1);\n  String n = String.format("scale-%05d", 2);\n}\n')
        self.commit("add call")
        problems = self.problems()
        self.assertEqual(1, len(problems))
        self.assertIn(TEST_FILE + ":3:", problems[0])

    def test_added_call_in_jmh_is_reported(self):
        self.write(JMH_FILE, 'class SomeBenchmark {\n  String name(int i) { return String.format("n%d", i); }\n}\n')
        self.commit("add benchmark")
        self.assertEqual(1, len(self.problems()))

    def test_call_split_over_two_lines_is_reported(self):
        self.write(JMH_FILE, 'class B {\n  String s = String.format(\n      "n%d", 1);\n}\n')
        self.commit("add split call")
        self.assertEqual(1, len(self.problems()))

    def test_explicit_locale_is_accepted(self):
        self.write(TEST_FILE, 'class SomeTest {\n  String n = String.format(Locale.ROOT, "%05d", 2);\n  String m = String.format(java.util.Locale.ROOT, "%d", 3);\n}\n')
        self.commit("add rooted call")
        self.assertEqual([], self.problems())

    def test_non_literal_first_argument_is_not_guessed(self):
        self.write(TEST_FILE, 'class SomeTest {\n  String n = String.format(locale, "%d", 2);\n  String m = String.format(PATTERN, 3);\n}\n')
        self.commit("add dynamic calls")
        self.assertEqual([], self.problems())

    def test_main_sources_are_left_to_forbidden_apis(self):
        self.write(MAIN_FILE, 'class Some {\n  String n = String.format("%d", 2);\n}\n')
        self.commit("add main call")
        self.assertEqual([], self.problems())

    def test_comments_are_ignored(self):
        self.write(TEST_FILE, 'class SomeTest {\n  // String.format("%d", 2) is locale dependent\n  /** String.format("%d", 2) */\n}\n')
        self.commit("add comments")
        self.assertEqual([], self.problems())

    def test_suppression_comment_is_honoured_on_same_or_previous_line(self):
        self.write(
            TEST_FILE,
            "class SomeTest {\n"
            '  String a = String.format("%d", 2); // locale-default-ok: asserting the platform default\n'
            "  // locale-default-ok: asserting the platform default\n"
            '  String b = String.format("%d", 3);\n'
            "}\n",
        )
        self.commit("add suppressed calls")
        self.assertEqual([], self.problems())

    def test_other_test_source_sets_are_covered(self):
        path = "dd-java-agent/instrumentation/foo/src/latestDepTest/java17/FooTest.java"
        self.write(path, 'class FooTest {\n  String n = String.format("%d", 2);\n}\n')
        self.commit("add latestDepTest")
        self.assertEqual(1, len(self.problems()))

    def test_main_returns_failure_exit_code(self):
        self.write(TEST_FILE, 'class SomeTest {\n  String n = String.format("%d", 2);\n}\n')
        self.commit("add call")
        self.assertEqual(1, check.main(["check", "HEAD~1", "HEAD"]))

    def test_main_returns_success_exit_code(self):
        self.write("README.md", "hello\n")
        self.commit("docs")
        self.assertEqual(0, check.main(["check", "HEAD~1", "HEAD"]))


if __name__ == "__main__":
    unittest.main()
