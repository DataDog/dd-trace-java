package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.events.BuildEventsHandler;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import javax.annotation.Nullable;

/** Test module abstraction that is used by build system instrumentations (e.g. Maven, Gradle) */
public interface BuildSystemModule {

  void setTag(String key, Object value);

  void setErrorInfo(Throwable error);

  void setSkipReason(String skipReason);

  void end(@Nullable Long endTime);

  long getId();

  BuildEventsHandler.ModuleInfo getModuleInfo();

  void onModuleExecutionResultReceived(ModuleExecutionResult result);
}
