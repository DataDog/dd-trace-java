package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.decorator.TestDecorator;

public interface BuildEventsHandler<T> {
  void onTestSessionStart(
      T sessionKey,
      TestDecorator sessionDecorator,
      String projectName,
      String startCommand,
      String buildSystemName,
      String buildSystemVersion);

  void onTestFrameworkDetected(T sessionKey, String frameworkName, String frameworkVersion);

  void onTestSessionFinish(T sessionKey);

  ModuleAndSessionId onTestModuleStart(T sessionKey, String moduleName);

  void onModuleTestFrameworkDetected(
      T sessionKey, String moduleName, String frameworkName, String frameworkVersion);

  void onTestModuleSkip(T sessionKey, String moduleName, String reason);

  void onTestModuleFail(T sessionKey, String moduleName, Throwable throwable);

  void onTestModuleFinish(T sessionKey, String moduleName);

  interface Factory {
    <U> BuildEventsHandler<U> create();
  }

  final class ModuleAndSessionId {
    public final long moduleId;
    public final long sessionId;

    public ModuleAndSessionId(long moduleId, long sessionId) {
      this.moduleId = moduleId;
      this.sessionId = sessionId;
    }
  }
}
