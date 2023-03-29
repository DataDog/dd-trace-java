package datadog.trace.api.civisibility.events.impl;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestModule;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.decorator.TestDecorator;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEventsHandlerImpl implements TestEventsHandler {

  private static final Logger log = LoggerFactory.getLogger(TestEventsHandlerImpl.class);

  private final DDTestModule testModule;

  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, DDTestSuite> inProgressTestSuites =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestDescriptor, DDTest> inProgressTests = new ConcurrentHashMap<>();

  public TestEventsHandlerImpl(TestDecorator testDecorator) {
    // FIXME ugly injection
    String modulePath = testDecorator.getModulePath();
    testModule = new DDTestModuleImpl(modulePath, Config.get(), testDecorator);
  }

  @Override
  public void onTestModuleFinish() {
    testModule.end(null);
  }

  @Override
  public void onTestSuiteStart(
      final String testSuiteName,
      final @Nullable Class<?> testClass,
      final @Nullable Collection<String> categories) {
    if (skipTrace(testClass)) {
      return;
    }

    if (!tryTestSuiteStart(testSuiteName, testClass)) {
      return;
    }

    DDTestSuite testSuite = testModule.testSuiteStart(testSuiteName, testClass, null);

    if (categories != null && !categories.isEmpty()) {
      testSuite.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories)), true));
    }

    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    inProgressTestSuites.put(descriptor, testSuite);
  }

  @Override
  public void onTestSuiteFinish(final String testSuiteName, final @Nullable Class<?> testClass) {
    if (skipTrace(testClass)) {
      return;
    }

    if (!tryTestSuiteFinish(testSuiteName, testClass)) {
      return;
    }

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuite testSuite = inProgressTestSuites.remove(testSuiteDescriptor);
    testSuite.end(null);
  }

  private boolean tryTestSuiteStart(String testSuiteName, Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    Integer counter = testSuiteNestedCallCounters.merge(testSuiteDescriptor, 1, Integer::sum);
    return counter == 1;
  }

  private boolean tryTestSuiteFinish(String testSuiteName, Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    Integer counter =
        testSuiteNestedCallCounters.merge(
            testSuiteDescriptor, -1, (a, b) -> a + b > 0 ? a + b : null);
    return counter == null;
  }

  @Override
  public void onTestSuiteSkip(String testSuiteName, Class<?> testClass, @Nullable String reason) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuite testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      // FIXME log?
      return;
    }
    testSuite.setSkipReason(reason);
  }

  @Override
  public void onTestSuiteFailure(
      String testSuiteName, Class<?> testClass, @Nullable Throwable throwable) {
    TestSuiteDescriptor descriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuite testSuite = inProgressTestSuites.get(descriptor);
    if (testSuite == null) {
      // FIXME log?
      return;
    }
    testSuite.setErrorInfo(throwable);
  }

  @Override
  public void onTestStart(
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable Collection<String> categories,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod) {
    if (skipTrace(testClass)) {
      return;
    }

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuite testSuite = inProgressTestSuites.get(testSuiteDescriptor);
    DDTest test = testSuite.testStart(testName, testMethod, null);

    if (testParameters != null) {
      test.setTag(Tags.TEST_PARAMETERS, testParameters);
    }
    if (categories != null && !categories.isEmpty()) {
      String json = toJson(Collections.singletonMap("category", toJson(categories)), true);
      test.setTag(Tags.TEST_TRAITS, json);
    }

    TestDescriptor testDescriptor = new TestDescriptor(testSuiteName, testClass, testName);
    inProgressTests.put(testDescriptor, test);
  }

  @Override
  public void onTestSkip(
      String testSuiteName, Class<?> testClass, String testName, @Nullable String reason) {
    TestDescriptor testDescriptor = new TestDescriptor(testSuiteName, testClass, testName);
    DDTest test = inProgressTests.get(testDescriptor);
    if (test == null) {
      // FIXME log?
      return;
    }
    test.setSkipReason(reason);
  }

  @Override
  public void onTestFailure(
      String testSuiteName, Class<?> testClass, String testName, @Nullable Throwable throwable) {
    TestDescriptor testDescriptor = new TestDescriptor(testSuiteName, testClass, testName);
    DDTest test = inProgressTests.get(testDescriptor);
    if (test == null) {
      // FIXME log?
      return;
    }
    test.setErrorInfo(throwable);
  }

  @Override
  public void onTestFinish(
      final String testSuiteName, final Class<?> testClass, final String testName) {
    if (skipTrace(testClass)) {
      return;
    }

    TestDescriptor testDescriptor = new TestDescriptor(testSuiteName, testClass, testName);
    DDTest test = inProgressTests.remove(testDescriptor);
    if (test == null) {
      // FIXME log a debug message?
      return;
    }
    test.end(null);
  }

  @Override
  public void onTestIgnore(
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable List<String> categories,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod,
      final @Nullable String reason) {
    if (skipTrace(testClass)) {
      return;
    }

    // FIXME duplicates what is available in onTestStart
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    DDTestSuite testSuite = inProgressTestSuites.get(testSuiteDescriptor);
    DDTest test = testSuite.testStart(testName, testMethod, null);

    if (testParameters != null) {
      test.setTag(Tags.TEST_PARAMETERS, testParameters);
    }
    if (categories != null && !categories.isEmpty()) {
      String json = toJson(Collections.singletonMap("category", toJson(categories)), true);
      test.setTag(Tags.TEST_TRAITS, json);
    }

    test.setSkipReason(reason);

    test.end(null);
  }

  private static boolean skipTrace(final Class<?> testClass) {
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }
}
