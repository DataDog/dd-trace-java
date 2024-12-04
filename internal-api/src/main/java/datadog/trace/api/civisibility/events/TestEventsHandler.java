package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import java.io.Closeable;
import java.lang.reflect.Method;
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
      TestFrameworkInstrumentation instrumentation);

  void onTestSuiteSkip(SuiteKey descriptor, @Nullable String reason);

  void onTestSuiteFailure(SuiteKey descriptor, @Nullable Throwable throwable);

  void onTestSuiteFinish(SuiteKey descriptor);

  void onTestStart(
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
      boolean isRetry);

  void onTestSkip(TestKey descriptor, @Nullable String reason);

  void onTestFailure(TestKey descriptor, @Nullable Throwable throwable);

  void onTestFinish(TestKey descriptor);

  void onTestIgnore(
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
      @Nullable String reason);

  boolean skip(TestIdentifier test);

  boolean shouldBeSkipped(TestIdentifier test);

  @Nonnull
  TestRetryPolicy retryPolicy(TestIdentifier test);

  boolean isNew(TestIdentifier test);

  boolean isFlaky(TestIdentifier test);

  @Override
  void close();

  interface Factory {
    <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
        String component,
        @Nullable ContextStore<SuiteKey, DDTestSuite> suiteStore,
        @Nullable ContextStore<TestKey, DDTest> testStore);
  }
}
