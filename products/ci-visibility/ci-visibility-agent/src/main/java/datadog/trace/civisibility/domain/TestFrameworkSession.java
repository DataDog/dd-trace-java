package datadog.trace.civisibility.domain;

import javax.annotation.Nullable;

/** Test session abstraction that is used by test framework instrumentations (e.g. JUnit, TestNG) */
public interface TestFrameworkSession {
  void end(Long startTime);

  TestFrameworkModule testModuleStart(String moduleName, @Nullable Long startTime);

  interface Factory {
    TestFrameworkSession startSession(String projectName, String component, Long startTime);
  }
}
