package datadog.trace.civisibility.events;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.civisibility.retry.NeverRetry;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

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
      TestFrameworkInstrumentation instrumentation,
      @Nullable Long startTime) {
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
  public void onTestSuiteFinish(SuiteKey descriptor, @Nullable Long endTime) {
    // do nothing
  }

  @Override
  public void onTestStart(
      SuiteKey suiteDescriptor,
      TestKey descriptor,
      String testName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nonnull TestSourceData testSourceData,
      boolean isRetry,
      @Nullable Long startTime) {
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
  public void onTestFinish(TestKey descriptor, @Nullable Long endTime) {
    // do nothing
  }

  @Override
  public void onTestIgnore(
      SuiteKey suiteDescriptor,
      TestKey testDescriptor,
      String testName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nonnull TestSourceData testSourceData,
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
  public TestRetryPolicy retryPolicy(TestIdentifier test, TestSourceData source) {
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
