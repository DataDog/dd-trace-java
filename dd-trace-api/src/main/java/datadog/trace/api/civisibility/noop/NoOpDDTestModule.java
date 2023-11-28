package datadog.trace.api.civisibility.noop;

import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSuite;
import javax.annotation.Nullable;

public class NoOpDDTestModule implements DDTestModule {
  static final DDTestModule INSTANCE = new NoOpDDTestModule();

  @Override
  public void setTag(String key, Object value) {}

  @Override
  public void setErrorInfo(Throwable error) {}

  @Override
  public void setSkipReason(String skipReason) {}

  @Override
  public void end(@Nullable Long endTime) {}

  @Override
  public DDTestSuite testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized) {
    return NoOpDDTestSuite.INSTANCE;
  }
}
