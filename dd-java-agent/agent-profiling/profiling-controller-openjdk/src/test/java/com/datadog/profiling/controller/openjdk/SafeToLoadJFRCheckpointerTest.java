package com.datadog.profiling.controller.openjdk;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.profiling.utils.ExcludedVersions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class SafeToLoadJFRCheckpointerTest {

  @Test
  public void testSafeToLoad() {
    Assumptions.assumeTrue(ExcludedVersions.isVersionExcluded());
    assertThrows(IllegalArgumentException.class, JFREventContextIntegration::new);
  }
}
