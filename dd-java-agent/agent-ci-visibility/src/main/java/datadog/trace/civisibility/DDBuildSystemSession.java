package datadog.trace.civisibility;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.civisibility.config.JvmInfo;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import javax.annotation.Nullable;

public interface DDBuildSystemSession {
  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void end(@Nullable Long endTime);

  DDBuildSystemModule testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs);

  ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo);

  interface Factory {
    DDBuildSystemSession startSession(
        String projectName, Path projectRoot, String component, Long startTime);
  }
}
