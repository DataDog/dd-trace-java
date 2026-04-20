package datadog.trace.civisibility.domain.manualapi;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.domain.TestImpl;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/**
 * Test suite that was created using manual API ({@link
 * datadog.trace.api.civisibility.CIVisibility}).
 */
public class ManualApiTestSuite implements DDTestSuite {

  private final TestSuiteImpl delegate;
  private final String frameworkName;

  public ManualApiTestSuite(TestSuiteImpl delegate, String frameworkName) {
    this.delegate = delegate;
    this.frameworkName = frameworkName;
  }

  @Override
  public void setTag(String key, Object value) {
    delegate.setTag(key, value);
  }

  @Override
  public void setErrorInfo(Throwable error) {
    delegate.setErrorInfo(error);
  }

  @Override
  public void setSkipReason(String skipReason) {
    delegate.setSkipReason(skipReason);
  }

  @Override
  public void end(@Nullable Long endTime) {
    delegate.end(endTime);
  }

  @Override
  public DDTest testStart(String testName, @Nullable Method testMethod, @Nullable Long startTime) {
    TestImpl test = delegate.testStart(testName, testMethod, startTime);
    test.setTag(Tags.TEST_FRAMEWORK, frameworkName);
    return test;
  }
}
