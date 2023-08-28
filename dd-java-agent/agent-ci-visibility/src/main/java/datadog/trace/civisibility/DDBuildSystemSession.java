package datadog.trace.civisibility;

import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.civisibility.config.JvmInfo;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import javax.annotation.Nullable;

/** Test session abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface DDBuildSystemSession extends DDTestSession {

  DDBuildSystemModule testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs);

  ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo);

  interface Factory {
    DDBuildSystemSession startSession(
        String projectName,
        Path projectRoot,
        String startCommand,
        String buildSystemName,
        Long startTime);
  }
}
