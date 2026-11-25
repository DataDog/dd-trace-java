package datadog.trace.civisibility;

import static datadog.trace.civisibility.CiVisibilitySystem.getLineProbesIncludedPackages;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.civisibility.coverage.file.instrumentation.CoverageInstrumentationFilter;
import org.junit.jupiter.api.Test;

class CiVisibilitySystemTest {

  private static final String[] EXCLUDED_PACKAGES = {"org/mockito/"};

  @Test
  void lineProbesCoverEveryClassNotExcludedWithoutPackagesToCover() {
    CoverageInstrumentationFilter filter =
        new CoverageInstrumentationFilter(
            getLineProbesIncludedPackages(new String[0]), EXCLUDED_PACKAGES);

    assertTrue(filter.test("com/example/Calc"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }

  @Test
  void lineProbesReadAnEmptyPrefixAsNoPackagesToCover() {
    // a build system's empty list reaches the test JVM as an empty property
    CoverageInstrumentationFilter filter =
        new CoverageInstrumentationFilter(
            getLineProbesIncludedPackages(new String[] {""}), EXCLUDED_PACKAGES);

    assertTrue(filter.test("com/example/Calc"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }

  @Test
  void lineProbesKeepThePackagesToCover() {
    String[] includedPackages = {"com/example/"};

    assertSame(includedPackages, getLineProbesIncludedPackages(includedPackages));
  }
}
