package datadog.trace.civisibility;

import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;

/** Test module abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface DDBuildSystemModule extends DDTestModule {
  long getId();

  BuildEventsHandler.ModuleInfo getModuleInfo();

  void onModuleExecutionResultReceived(ModuleExecutionResult result);
}
