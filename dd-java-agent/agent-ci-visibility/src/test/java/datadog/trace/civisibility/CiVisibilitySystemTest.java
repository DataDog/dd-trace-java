package datadog.trace.civisibility;

import static datadog.trace.civisibility.CiVisibilitySystem.getLineProbesIncludedPackages;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.civisibility.coverage.probe.ProbeClassFilter;
import org.junit.jupiter.api.Test;

class CiVisibilitySystemTest {

  private static final String[] EXCLUDED_PACKAGES = {"org/mockito/"};

  @Test
  void lineProbesCoverEveryClassNotExcludedWithoutPackagesToCover() {
    ProbeClassFilter filter =
        new ProbeClassFilter(getLineProbesIncludedPackages(new String[0]), EXCLUDED_PACKAGES, true);

    assertTrue(filter.test("com/example/Calc"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }

  @Test
  void lineProbesReadAnEmptyPrefixAsNoPackagesToCover() {
    // a build system's empty list reaches the test JVM as an empty property
    ProbeClassFilter filter =
        new ProbeClassFilter(
            getLineProbesIncludedPackages(new String[] {""}), EXCLUDED_PACKAGES, true);

    assertTrue(filter.test("com/example/Calc"));
    assertFalse(filter.test("org/mockito/Mockito"));
  }

  @Test
  void lineProbesKeepThePackagesToCover() {
    String[] includedPackages = {"com/example/"};

    assertSame(includedPackages, getLineProbesIncludedPackages(includedPackages));
  }
}
