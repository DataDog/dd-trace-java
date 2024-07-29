package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.domain.ModuleLayout;
import datadog.trace.civisibility.config.JvmInfo;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Test session abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface BuildSystemSession {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void end(@Nullable Long endTime);

  BuildSystemModule testModuleStart(
      String moduleName, @Nullable Long startTime, ModuleLayout moduleLayout);

  ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo);

  interface Factory {
    BuildSystemSession startSession(
        String projectName,
        Path projectRoot,
        String startCommand,
        String buildSystemName,
        Long startTime);
  }
}
