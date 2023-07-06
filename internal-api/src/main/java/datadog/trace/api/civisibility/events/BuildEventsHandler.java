package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import java.nio.file.Path;
import java.util.Map;

public interface BuildEventsHandler<T> {
  void onTestSessionStart(
      T sessionKey,
      String projectName,
      Path projectRoot,
      String startCommand,
      String buildSystemName,
      String buildSystemVersion);

  void onTestFrameworkDetected(T sessionKey, String frameworkName, String frameworkVersion);

  void onTestSessionFail(T sessionKey, Throwable throwable);

  void onTestSessionFinish(T sessionKey);

  ModuleInfo onTestModuleStart(
      T sessionKey, String moduleName, String startCommand, Map<String, Object> additionalTags);

  void onModuleTestFrameworkDetected(
      T sessionKey, String moduleName, String frameworkName, String frameworkVersion);

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
