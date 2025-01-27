package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import java.io.Closeable;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface TestEventsHandler<SuiteKey, TestKey> extends Closeable {

  /**
   * @param testFramework Name of the testing framework that executes the suite.
   * @param instrumentation Instrumentation that emits the event. Can differ from the testing
   *     framework, because one instrumentation can support multiple frameworks. For example, there
   *     are many testing frameworks based on JUnit 5. For some of those frameworks we have
   *     dedicated instrumentations, while others are handled with "generic" JUnit 5 instrumentation
   */
  void onTestSuiteStart(
      SuiteKey descriptor,
      String testSuiteName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable Class<?> testClass,
      @Nullable Collection<String> categories,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation,
      @Nullable Long startTime);

  void onTestSuiteSkip(SuiteKey descriptor, @Nullable String reason);

  void onTestSuiteFailure(SuiteKey descriptor, @Nullable Throwable throwable);

  void onTestSuiteFinish(SuiteKey descriptor, @Nullable Long endTime);

  /**
   * Reports a "test started" event
   *
   * @param suiteDescriptor a descriptor uniquely identifying the test's suite
   * @param descriptor a descriptor uniquely identifying the test
   * @param testName the name of the test case
   * @param testFramework name of the testing framework used to run the test case
   * @param testFrameworkVersion version of the testing framework used to run the test case
   * @param testParameters test parameters (as stringified JSON) if this is a parameterized test
   *     case
   * @param categories test categories (or test tags) if the test case is marked with any
   * @param testSourceData metadata for locating the source code for the test case
   * @param retryReason if this is a retry of the previously executed test case, the reason for
   *     retrying
   * @param startTime the timestamp of the test execution start ({@code null} for current timestamp)
   */
  void onTestStart(
      SuiteKey suiteDescriptor,
      TestKey descriptor,
      String testName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nonnull TestSourceData testSourceData,
      @Nullable String retryReason,
      @Nullable Long startTime);

  void onTestSkip(TestKey descriptor, @Nullable String reason);

  void onTestFailure(TestKey descriptor, @Nullable Throwable throwable);

  void onTestFinish(TestKey descriptor, @Nullable Long endTime);

  void onTestIgnore(
      SuiteKey suiteDescriptor,
      TestKey testDescriptor,
      String testName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nonnull TestSourceData testSourceData,
      @Nullable String reason);

  @Nonnull
  TestRetryPolicy retryPolicy(TestIdentifier test, TestSourceData source);

  boolean isNew(TestIdentifier test);

  boolean isFlaky(TestIdentifier test);

  boolean isSkippable(TestIdentifier test);

  @Override
  void close();

  interface Factory {
    <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
        String component,
        @Nullable ContextStore<SuiteKey, DDTestSuite> suiteStore,
        @Nullable ContextStore<TestKey, DDTest> testStore);
  }
}
