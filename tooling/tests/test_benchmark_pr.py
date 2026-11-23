from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_pr import (
    MARKER_RE,
    format_number,
    missing_classes,
    parse_benchmark_list,
    glob_to_regex,
    includes_regex,
    parse_mapping,
    project_dir,
    render_section,
    required_benchmarks,
)

ROOT = Path(__file__).resolve().parents[2]


def result(benchmark, score, alloc=None, params=None, error=0.5, mode="thrpt"):
    secondary = {} if alloc is None else {"gc.alloc.rate.norm": {"score": alloc}}
    return {
        "benchmark": benchmark,
        "mode": mode,
        "params": params or {},
        "primaryMetric": {"score": score, "scoreError": error, "scoreUnit": "ops/us"},
        "secondaryMetrics": secondary,
    }


class GlobTest(unittest.TestCase):
    def test_star_stays_in_a_segment(self):
        self.assertTrue(glob_to_regex("a/*.java").fullmatch("a/B.java"))
        self.assertFalse(glob_to_regex("a/*.java").fullmatch("a/b/C.java"))

    def test_double_star_crosses_segments(self):
        self.assertTrue(glob_to_regex("a/**/C.java").fullmatch("a/C.java"))
        self.assertTrue(glob_to_regex("a/**/C.java").fullmatch("a/b/c/C.java"))
        self.assertTrue(glob_to_regex("a/**").fullmatch("a/b/c"))

    def test_other_characters_are_literal(self):
        self.assertFalse(glob_to_regex("a.java").fullmatch("ab java"))


class MappingTest(unittest.TestCase):
    RULES = parse_mapping(
        "# comment\n"
        "\n"
        "lib/Table.java  :lib  TableBenchmark  # trailing comment\n"
        "lib/*Store.java :lib  StoreBenchmark TableBenchmark\n"
        "core/Span.java  :core SpanBenchmark\n"
    )

    def test_parses_rules_and_skips_comments(self):
        self.assertEqual([r.line_number for r in self.RULES], [3, 4, 5])
        self.assertEqual(self.RULES[1].benchmarks, ["StoreBenchmark", "TableBenchmark"])

    def test_malformed_rule_names_its_line(self):
        with self.assertRaisesRegex(ValueError, r":2: "):
            parse_mapping("ok/A.java :lib ABenchmark\nmissing/project ABenchmark\n")

    def test_required_benchmarks_are_grouped_by_project_and_deduplicated(self):
        required = required_benchmarks(
            self.RULES, ["lib/Table.java", "lib/HashStore.java", "core/Span.java", "README.md"]
        )
        self.assertEqual(
            required,
            {":core": ["SpanBenchmark"], ":lib": ["StoreBenchmark", "TableBenchmark"]},
        )

    def test_unmapped_changes_require_nothing(self):
        self.assertEqual(required_benchmarks(self.RULES, ["docs/x.md"]), {})

    def test_the_repository_mapping_parses_and_names_existing_benchmarks(self):
        rules = parse_mapping((ROOT / ".github/required-benchmarks.txt").read_text())
        self.assertTrue(rules)
        for rule in rules:
            sources = ROOT / project_dir(rule.project) / "src" / "jmh"
            for name in rule.benchmarks:
                self.assertTrue(
                    list(sources.rglob(f"{name}.java")),
                    f"line {rule.line_number}: no {name}.java under {sources}",
                )


class RunHelpersTest(unittest.TestCase):
    def test_project_dir(self):
        self.assertEqual(project_dir(":internal-api"), "internal-api")
        self.assertEqual(project_dir(":dd-java-agent:agent-tooling"), "dd-java-agent/agent-tooling")

    def test_includes_match_whole_class_names_in_any_package(self):
        regex = re.compile(includes_regex(["HashtableD1Benchmark"]))
        self.assertTrue(regex.search("datadog.trace.util.HashtableD1Benchmark.iterate"))
        self.assertFalse(regex.search("datadog.trace.util.FlatHashtableD1Benchmark.iterate"))


class CoverageTest(unittest.TestCase):
    def test_classes_without_results_are_missing(self):
        results = [result("x.TableBenchmark.get", 1.0)]
        self.assertEqual(
            missing_classes(["TableBenchmark", "StoreBenchmark"], results), ["StoreBenchmark"]
        )

    def test_a_jmh_listing_gives_its_classes(self):
        listing = "Benchmarks: \nx.y.TableBenchmark.get\nx.y.TableBenchmark.put\nx.StoreBenchmark.scan\n"
        self.assertEqual(parse_benchmark_list(listing), {"TableBenchmark", "StoreBenchmark"})


class SectionTest(unittest.TestCase):
    BASE = "a" * 40
    HEAD = "b" * 40

    def section(self, base_results, head_results):
        return render_section(
            self.BASE, self.HEAD, ["TableBenchmark"], base_results, head_results, "JDK: test."
        )

    def test_marker_records_commits_and_benchmarks(self):
        marker = MARKER_RE.search(self.section([], []))
        self.assertEqual(marker["base"], self.BASE)
        self.assertEqual(marker["head"], self.HEAD)
        self.assertEqual(marker["benchmarks"], "TableBenchmark")

    def test_rows_pair_base_and_head_by_benchmark_and_params(self):
        text = self.section(
            [result("x.TableBenchmark.get", 100.0, alloc=0.0, params={"size": "8"})],
            [result("x.TableBenchmark.get", 110.0, alloc=16.0, params={"size": "8"})],
        )
        row = [line for line in text.splitlines() if "TableBenchmark.get" in line][0]
        self.assertIn("| thrpt | size=8 |", row)
        self.assertIn("+10.0%", row)
        self.assertIn("| 0.0 | 16.0 |", row)

    def test_extra_jmh_arguments_mark_the_results_as_a_spot_check(self):
        text = render_section(
            self.BASE, self.HEAD, ["TableBenchmark"], [], [], "JDK: test.", ["-f", "1"]
        )
        self.assertIn("overridden by `-f 1` (a spot check, not a full run)", text)
        self.assertNotIn("spot check", self.section([], []))

    def test_large_numbers_use_suffixes(self):
        self.assertEqual(format_number(11410000), "11.41M")
        self.assertEqual(format_number(254.7), "254.7")
        self.assertEqual(format_number(0.0123), "0.0123")

    def test_each_mode_gets_its_own_row(self):
        text = self.section(
            [result("x.TableBenchmark.get", 100.0), result("x.TableBenchmark.get", 0.01, mode="avgt")],
            [result("x.TableBenchmark.get", 110.0), result("x.TableBenchmark.get", 0.009, mode="avgt")],
        )
        rows = [line for line in text.splitlines() if "TableBenchmark.get" in line]
        self.assertEqual(len(rows), 2)
        self.assertTrue(any("| thrpt |" in r for r in rows))
        self.assertTrue(any("| avgt |" in r for r in rows))

    def test_classes_new_on_head_are_named(self):
        text = render_section(
            self.BASE, self.HEAD, ["TableBenchmark"], [], [], "JDK: test.", (), ["TableBenchmark"]
        )
        self.assertIn("Not on the base commit, added by this change: `TableBenchmark`.", text)

    def test_a_benchmark_missing_on_one_side_still_gets_a_row(self):
        text = self.section([], [result("x.TableBenchmark.added", 5.0)])
        row = [line for line in text.splitlines() if "TableBenchmark.added" in line][0]
        self.assertIn("| – |", row)


if __name__ == "__main__":
    unittest.main()
