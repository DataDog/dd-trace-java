package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.api.civisibility.domain.JavaAgent;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

public interface BuildEventsHandler<SessionKey> {
  void onTestSessionStart(
      SessionKey sessionKey,
      String projectName,
      Path projectRoot,
      String startCommand,
      String buildSystemName,
      String buildSystemVersion,
      Map<String, Object> additionalTags);

  void onTestSessionFail(SessionKey sessionKey, Throwable throwable);

  void onTestSessionFinish(SessionKey sessionKey);

  void onBuildTaskStart(SessionKey sessionKey, String taskName, Map<String, Object> additionalTags);

  void onBuildTaskFail(SessionKey sessionKey, String taskName, Throwable throwable);

  void onBuildTaskFinish(SessionKey sessionKey, String taskName);

  BuildModuleSettings onTestModuleStart(
      SessionKey sessionKey,
      String moduleName,
      BuildModuleLayout moduleLayout,
      @Nullable Path jvmExecutable,
      @Nullable Collection<Path> classpath,
      @Nullable JavaAgent jacocoAgent,
      @Nullable Map<String, Object> additionalTags);

  void onTestModuleSkip(SessionKey sessionKey, String moduleName, String reason);

  void onTestModuleFail(SessionKey sessionKey, String moduleName, Throwable throwable);

  void onTestModuleFinish(SessionKey sessionKey, String moduleName);

  BuildSessionSettings getSessionSettings(SessionKey sessionKey);

  BuildModuleSettings getModuleSettings(SessionKey sessionKey, String moduleName);

  interface Factory {
    <U> BuildEventsHandler<U> create();
  }
}
