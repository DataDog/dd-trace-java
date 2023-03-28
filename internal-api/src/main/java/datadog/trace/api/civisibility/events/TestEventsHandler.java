package datadog.trace.api.civisibility.events;

import datadog.trace.api.civisibility.decorator.TestDecorator;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

public interface TestEventsHandler {
  void onTestModuleStart(@Nullable String version);

  void onTestModuleFinish();

  void onTestSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable String version,
      @Nullable Collection<String> categories);

  void onTestSuiteFinish(String testSuiteName, @Nullable Class<?> testClass);

  void onSkip(@Nullable String reason);

  void onFailure(@Nullable Throwable throwable);

  void onTestStart(
      String testSuiteName,
      String testName,
      @Nullable String testParameters,
      @Nullable Collection<String> categories,
      @Nullable String version,
      @Nullable Class<?> testClass,
      @Nullable Method testMethod);

  void onTestFinish(String testSuiteName, Class<?> testClass);

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
