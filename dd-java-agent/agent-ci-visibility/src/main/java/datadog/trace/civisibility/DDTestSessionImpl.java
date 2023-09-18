package datadog.trace.civisibility;

import datadog.trace.api.civisibility.CIVisibility;
import datadog.trace.api.civisibility.DDTestSession;
import datadog.trace.api.civisibility.config.JvmInfo;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import java.nio.file.Path;
import javax.annotation.Nullable;

public abstract class DDTestSessionImpl implements DDTestSession {

  public abstract DDTestModuleImpl testModuleStart(String moduleName, @Nullable Long startTime);

  public abstract ModuleExecutionSettings getModuleExecutionSettings(JvmInfo jvmInfo);

  public interface SessionImplFactory extends CIVisibility.SessionFactory {
    DDTestSessionImpl startSession(
        String projectName, Path projectRoot, String component, Long startTime);
  }
}
