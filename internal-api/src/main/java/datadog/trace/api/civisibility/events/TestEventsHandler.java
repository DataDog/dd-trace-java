package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import java.io.Closeable;
import java.lang.reflect.Method;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface TestEventsHandler extends Closeable {

  /**
   * @param testFramework Name of the testing framework that executes the suite.
   * @param instrumentation Instrumentation that emits the event. Can differ from the testing
   *     framework, because one instrumentation can support multiple frameworks. For example, there
   *     are many testing frameworks based on JUnit 5. For some of those frameworks we have
   *     dedicated instrumentations, while others are handled with "generic" JUnit 5 instrumentation
   *     .
   */
  void onTestSuiteStart(
      String testSuiteName,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable Class<?> testClass,
      @Nullable Collection<String> categories,
      boolean parallelized,
      TestFrameworkInstrumentation instrumentation);

  void onTestSuiteFinish(String testSuiteName, @Nullable Class<?> testClass);

  void onTestSuiteSkip(String testSuiteName, Class<?> testClass, @Nullable String reason);

  void onTestSuiteFailure(String testSuiteName, Class<?> testClass, @Nullable Throwable throwable);

  void onTestStart(
      String testSuiteName,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nullable Class<?> testClass,
      @Nullable String testMethodName,
      @Nullable Method testMethod);

  void onTestSkip(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters,
      @Nullable String reason);

  void onTestFailure(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters,
      @Nullable Throwable throwable);

  void onTestFinish(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testParameters);

  void onTestIgnore(
      String testSuiteName,
      String testName,
      @Nullable Object testQualifier,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nullable Class<?> testClass,
      @Nullable String testMethodName,
      @Nullable Method testMethod,
      @Nullable String reason);

  boolean skip(TestIdentifier test);

  @Nonnull
  TestRetryPolicy retryPolicy(TestIdentifier test);

  @Override
  void close();

  interface Factory {
    TestEventsHandler create(String component);
  }
}
