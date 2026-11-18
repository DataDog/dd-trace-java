package com.datadog.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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

  /** One provider per suite, so each configuration is parsed once. */
  private static final Map<String, Provider> PROVIDERS = new HashMap<>();

  static Stream<String> caseFiles() throws IOException {
    final List<String> caseFiles = new ArrayList<>();
    for (final String suite : SUITES) {
      for (final String file : ScaleSuites.load(suite).caseFiles()) {
        caseFiles.add(suite + "/" + file);
      }
    }
    return caseFiles.stream();
  }

  @AfterAll
  static void shutDownProviders() {
    PROVIDERS.values().forEach(Provider::shutdown);
    PROVIDERS.clear();
  }

  @Test
  void benchmarkListsEveryCaseFile() throws ReflectiveOperationException, IOException {
    final List<String> found = new ArrayList<>();
    caseFiles().forEach(found::add);
    assertEquals(benchmarkedCaseFiles(), found);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("caseFiles")
  void evaluatesEveryCase(final String caseFile) throws IOException {
    final int slash = caseFile.indexOf('/');
    final String name = caseFile.substring(0, slash);
    final ScaleSuites.Suite suite = ScaleSuites.load(name);
    Provider provider = PROVIDERS.get(name);
    if (provider == null) {
      provider = suite.provider();
      PROVIDERS.put(name, provider);
    }
    for (final ScaleCase scaleCase : suite.cases(caseFile.substring(slash + 1))) {
      // Twice: a cache or index built on the first evaluation must give the same result.
      scaleCase.check(scaleCase.evaluate(provider));
      scaleCase.check(scaleCase.evaluate(provider));
    }
  }

  /**
   * The {@code @Param} values of {@code ScaleSuiteBenchmark.cases}. The test source set cannot
   * compile against the jmh one, so the build adds the benchmark classes to this test's runtime
   * classpath and the annotation is read by name.
   */
  private static List<String> benchmarkedCaseFiles() throws ReflectiveOperationException {
    final Field cases =
        Class.forName("com.datadog.openfeature.ScaleSuiteBenchmark").getField("cases");
    for (final Annotation annotation : cases.getAnnotations()) {
      final Class<? extends Annotation> type = annotation.annotationType();
      if ("org.openjdk.jmh.annotations.Param".equals(type.getName())) {
        return Arrays.asList((String[]) type.getMethod("value").invoke(annotation));
      }
    }
    throw new AssertionError("ScaleSuiteBenchmark.cases has no @Param");
  }
}
