package datadog.trace.civisibility.events;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.civisibility.retry.NeverRetry;
import java.lang.reflect.Method;
import java.util.Collection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NoOpTestEventsHandler<SuiteKey, TestKey>
    implements TestEventsHandler<SuiteKey, TestKey> {

  @Override
  public void onTestSuiteStart(
      SuiteKey descriptor,
      String testSuiteName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable Class<?> testClass,
      @Nullable Collection<String> categories,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation) {
    // do nothing
  }

  @Override
  public void onTestSuiteSkip(SuiteKey descriptor, @Nullable String reason) {
    // do nothing
  }

  @Override
  public void onTestSuiteFailure(SuiteKey descriptor, @Nullable Throwable throwable) {
    // do nothing
  }

  @Override
  public void onTestSuiteFinish(SuiteKey descriptor) {
    // do nothing
  }

  @Override
  public void onTestStart(
      SuiteKey suiteDescriptor,
      TestKey descriptor,
      String testSuiteName,
      String testName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nullable Class<?> testClass,
      @Nullable String testMethodName,
      @Nullable Method testMethod,
      boolean isRetry) {
    // do nothing
  }

  @Override
  public void onTestSkip(TestKey descriptor, @Nullable String reason) {
    // do nothing
  }

  @Override
  public void onTestFailure(TestKey descriptor, @Nullable Throwable throwable) {
    // do nothing
  }

  @Override
  public void onTestFinish(TestKey descriptor) {
    // do nothing
  }

  @Override
  public void onTestIgnore(
      SuiteKey suiteDescriptor,
      TestKey testDescriptor,
      String testSuiteName,
      String testName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nullable Class<?> testClass,
      @Nullable String testMethodName,
      @Nullable Method testMethod,
      @Nullable String reason) {
    // do nothing
  }

  @Override
  public boolean skip(TestIdentifier test) {
    return false;
  }

  @Override
  public boolean shouldBeSkipped(TestIdentifier test) {
    return false;
  }

  @NotNull
  @Override
  public TestRetryPolicy retryPolicy(TestIdentifier test) {
    return NeverRetry.INSTANCE;
  }

  @Override
  public boolean isNew(TestIdentifier test) {
    return false;
  }

  @Override
  public boolean isFlaky(TestIdentifier test) {
    return false;
  }

  @Override
  public void close() {
    // do nothing
  }

  public static final class Factory implements TestEventsHandler.Factory {
    @Override
    public <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
        String component,
        @Nullable ContextStore<SuiteKey, DDTestSuite> suiteStore,
        @Nullable ContextStore<TestKey, DDTest> testStore) {
      return new NoOpTestEventsHandler<>();
    }
  }
}
