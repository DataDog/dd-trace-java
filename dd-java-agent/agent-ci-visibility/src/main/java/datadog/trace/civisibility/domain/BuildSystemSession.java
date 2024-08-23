package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.civisibility.config.JvmInfo;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Test session abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface BuildSystemSession {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void end(@Nullable Long endTime);

  BuildSystemModule testModuleStart(
      String moduleName, @Nullable Long startTime, BuildModuleLayout moduleLayout, JvmInfo jvmInfo);

  BuildSessionSettings getSettings();

  interface Factory {
    BuildSystemSession startSession(
        String projectName,
        Path projectRoot,
        String startCommand,
        String buildSystemName,
        Long startTime);
  }
}
