package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.civisibility.config.JvmInfo;
import java.nio.file.Path;
import java.util.Collection;
import javax.annotation.Nullable;

/** Test session abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface BuildSystemSession {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void end(@Nullable Long endTime);

  BuildSystemModule testModuleStart(
      String moduleName,
      @Nullable Long startTime,
      BuildModuleLayout moduleLayout,
      JvmInfo jvmInfo,
      @Nullable Collection<Path> classpath,
      @Nullable JavaAgent jacocoAgent);

  AgentSpan testTaskStart(String taskName);

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
