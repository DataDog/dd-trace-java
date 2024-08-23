package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.BuildModuleSettings;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import java.nio.file.Path;
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

  BuildModuleSettings onTestModuleStart(
      SessionKey sessionKey,
      String moduleName,
      BuildModuleLayout moduleLayout,
      @Nullable Path jvmExecutable,
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
