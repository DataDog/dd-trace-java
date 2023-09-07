package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

public interface BuildEventsHandler<T> {
  void onTestSessionStart(
      T sessionKey,
      String projectName,
      Path projectRoot,
      String startCommand,
      String buildSystemName,
      String buildSystemVersion);

  void onTestSessionFail(T sessionKey, Throwable throwable);

  void onTestSessionFinish(T sessionKey);

  ModuleInfo onTestModuleStart(
      T sessionKey,
      String moduleName,
      Collection<File> outputClassesDirs,
      @Nullable Map<String, Object> additionalTags);

  void onTestModuleSkip(T sessionKey, String moduleName, String reason);

  void onTestModuleFail(T sessionKey, String moduleName, Throwable throwable);

  void onTestModuleFinish(T sessionKey, String moduleName);

  ModuleExecutionSettings getModuleExecutionSettings(T sessionKey, Path jvmExecutablePath);

  interface Factory {
    <U> BuildEventsHandler<U> create();
  }

  final class ModuleInfo {
    public final long moduleId;
    public final long sessionId;
    public final String signalServerHost;
    public final int signalServerPort;

    public ModuleInfo(
        long moduleId, long sessionId, String signalServerHost, int signalServerPort) {
      this.moduleId = moduleId;
      this.sessionId = sessionId;
      this.signalServerHost = signalServerHost;
      this.signalServerPort = signalServerPort;
    }
  }
}
