package datadog.trace.civisibility;

import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.civisibility.config.JvmInfo;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nullable;

public abstract class DDTestSessionImpl
    implements DDTestSession, DDTestFrameworkSession, DDBuildSystemSession {

  public DDTestModuleImpl testModuleStart(String moduleName, @Nullable Long startTime) {
    return testModuleStart(moduleName, startTime, Collections.emptyList());
  }

  public abstract DDTestModuleImpl testModuleStart(
      String moduleName, @Nullable Long startTime, Collection<File> outputClassesDirs);

  public abstract ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo);

  public interface SessionImplFactory
      extends CIVisibility.SessionFactory, DDBuildSystemSession.Factory {
    DDTestSessionImpl startSession(
        String projectName, Path projectRoot, String component, Long startTime);
  }
}
