package datadog.trace.civisibility;

import datadog.trace.api.civisibility.config.SkippableTest;
import javax.annotation.Nullable;

public interface DDTestFrameworkModule {
  DDTestSuiteImpl testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized);

  boolean skip(SkippableTest test);

  void end(Long startTime);
}
