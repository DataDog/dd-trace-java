package com.datadog.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Evaluates every case of the opt-in scale suites of the shared FFE fixtures, as {@code
 * CanonicalFixtureTest} does for the core cases. The scale benchmarks run the same check, so a
 * change that resolves a large configuration wrongly fails here instead of only in a manual run.
 */
class ScaleSuiteTest {
  private static final List<String> SUITES = Arrays.asList("scale", "scale-json");

  /** The case files that {@code ScaleSuiteBenchmark} lists in its {@code cases} parameter. */
  private static final List<String> BENCHMARKED_CASE_FILES =
      Arrays.asList(
          "scale/flags",
          "scale/numeric",
          "scale/one-of",
          "scale/one-of-many",
          "scale/regex",
          "scale/regex-many",
          "scale/rules",
          "scale/rules-sweep",
          "scale/semver",
          "scale/splits",
          "scale/time-windows",
          "scale/variations",
          "scale/wide-context",
          "scale-json/json-shapes",
          "scale-json/json-sizes",
          "scale-json/json-values");

  static Stream<String> caseFiles() throws IOException {
    final List<String> caseFiles = new ArrayList<>();
    for (final String suite : SUITES) {
      for (final String file : ScaleSuites.load(suite).caseFiles()) {
        caseFiles.add(suite + "/" + file);
      }
    }
    return caseFiles.stream();
  }

  @Test
  void benchmarkListsEveryCaseFile() throws IOException {
    final List<String> found = new ArrayList<>();
    caseFiles().forEach(found::add);
    assertEquals(BENCHMARKED_CASE_FILES, found);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("caseFiles")
  void evaluatesEveryCase(final String caseFile) throws IOException {
    final int slash = caseFile.indexOf('/');
    final ScaleSuites.Suite suite = ScaleSuites.load(caseFile.substring(0, slash));
    final Provider provider = suite.provider();
    try {
      for (final ScaleCase scaleCase : suite.cases(caseFile.substring(slash + 1))) {
        // Twice: a cache or index built on the first evaluation must give the same result.
        scaleCase.check(scaleCase.evaluate(provider));
        scaleCase.check(scaleCase.evaluate(provider));
      }
    } finally {
      provider.shutdown();
    }
  }
}
