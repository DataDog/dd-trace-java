package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.civisibility.config.JvmInfo;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import javax.annotation.Nullable;

/** Test session abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface BuildSystemSession {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void end(@Nullable Long endTime);

  BuildSystemModule testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs);

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
