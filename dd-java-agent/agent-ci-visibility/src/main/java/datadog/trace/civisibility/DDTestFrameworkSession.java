package datadog.trace.civisibility;

import java.nio.file.Path;
import javax.annotation.Nullable;

/** Test session abstraction that is used by test framework instrumentations (e.g. JUnit, TestNG) */
public interface DDTestFrameworkSession {
  void end(Long startTime);

  DDTestFrameworkModule testModuleStart(String moduleName, @Nullable Long startTime);

  interface Factory {
    DDTestFrameworkSession startSession(
        String projectName, Path projectRoot, String component, Long startTime);
  }
}
