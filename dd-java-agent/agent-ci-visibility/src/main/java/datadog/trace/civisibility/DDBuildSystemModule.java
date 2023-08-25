package datadog.trace.civisibility;

import datadog.trace.api.civisibility.events.BuildEventsHandler;
import javax.annotation.Nullable;

public interface DDBuildSystemModule {
  void setTag(String key, Object value);

  void setSkipReason(String skipReason);

  void setErrorInfo(Throwable error);

  BuildEventsHandler.ModuleInfo getModuleInfo();

  void end(@Nullable Long endTime);
}
