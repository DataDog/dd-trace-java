package datadog.trace.api.civisibility.noop;

import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSession;
import javax.annotation.Nullable;

public class NoOpDDTestSession implements DDTestSession {
  public static final DDTestSession INSTANCE = new NoOpDDTestSession();

  @Override
  public void setTag(String key, Object value) {}

  @Override
  public void setErrorInfo(Throwable error) {}

  @Override
  public void setSkipReason(String skipReason) {}

  @Override
  public void end(@Nullable Long endTime) {}

  @Override
  public DDTestModule testModuleStart(String moduleName, @Nullable Long startTime) {
    return NoOpDDTestModule.INSTANCE;
  }
}
