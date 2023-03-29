package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.decorator.TestDecorator;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

// FIXME move testSuiteDescriptor, testDescriptor classes to interface level?
public interface TestEventsHandler {
  void onTestModuleStart(@Nullable String version);

  void onTestModuleFinish();

  void onTestSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable String version,
      @Nullable Collection<String> categories);

  void onTestSuiteFinish(String testSuiteName, @Nullable Class<?> testClass);

  void onTestSuiteSkip(String testSuiteName, Class<?> testClass, @Nullable String reason);

  void onTestSuiteFailure(String testSuiteName, Class<?> testClass, @Nullable Throwable throwable);

  void onTestStart(
      String testSuiteName,
      String testName,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nullable String version,
      @Nullable Class<?> testClass,
      @Nullable Method testMethod);

  void onTestSkip(
      String testSuiteName, Class<?> testClass, String testName, @Nullable String reason);

  void onTestFailure(
      String testSuiteName, Class<?> testClass, String testName, @Nullable Throwable throwable);

  void onTestFinish(String testSuiteName, Class<?> testClass, String testName);

  void onTestIgnore(
      String testSuiteName,
      String testName,
      @Nullable String testParameters,
      @Nullable List<String> categories,
      @Nullable String version,
      @Nullable Class<?> testClass,
      @Nullable Method testMethod,
      @Nullable String reason);

  boolean isTestSuiteInProgress();

  interface Factory {
    TestEventsHandler create(TestDecorator decorator);
  }
}
