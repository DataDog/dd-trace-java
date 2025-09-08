package datadog.trace.civisibility.events;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.civisibility.execution.Regular;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
  public void onTestFinish(
      TestKey descriptor, @Nullable Long endTime, @Nullable TestExecutionHistory executionHistory) {
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
      @Nullable String reason,
      @Nullable TestExecutionHistory testExecutionHistory) {
    // do nothing
  }

  @Override
  public SkipReason skipReason(TestIdentifier test) {
    return null;
  }

  @Nonnull
  @Override
  public TestExecutionPolicy executionPolicy(
      TestIdentifier test, TestSourceData source, Collection<String> testTags) {
    return Regular.INSTANCE;
  }

  @Override
  public int executionPriority(
      @Nullable TestIdentifier test, @Nonnull TestSourceData testSourceData) {
    return 0;
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
        @Nullable ContextStore<TestKey, DDTest> testStore,
        Collection<LibraryCapability> capabilities) {
      return new NoOpTestEventsHandler<>();
    }
  }
}
