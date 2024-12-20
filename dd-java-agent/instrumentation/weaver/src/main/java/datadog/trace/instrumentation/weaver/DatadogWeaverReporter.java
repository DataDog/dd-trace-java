package datadog.trace.instrumentation.weaver;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import sbt.testing.TaskDef;
import weaver.framework.SuiteFinished;
import weaver.framework.SuiteStarted;
import weaver.framework.TestFinished;

public class DatadogWeaverReporter {

  private static final String TEST_FRAMEWORK = "weaver";
  private static final String TEST_FRAMEWORK_VERSION = WeaverUtils.getWeaverVersion();

  private static final TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler =
      InstrumentationBridge.createTestEventsHandler("weaver", null, null);

  public static void onSuiteStart(SuiteStarted event) {
    String testSuiteName = event.name();
    Class<?> testClass = WeaverUtils.getClass(testSuiteName);
    Collection<String> categories = Collections.emptyList();
    boolean parallelized = true;

    eventHandler.onTestSuiteStart(
        new TestSuiteDescriptor(testSuiteName, testClass),
        testSuiteName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testClass,
        categories,
        parallelized,
        TestFrameworkInstrumentation.OTHER); // todo: update instrumentation framework
  }

  public static void onSuiteFinish(SuiteFinished event) {
    String testSuiteName = event.name();
    Class<?> testClass = WeaverUtils.getClass(testSuiteName);

    eventHandler.onTestSuiteFinish(new TestSuiteDescriptor(testSuiteName, testClass));
  }

  public static void onTestFinished(TestFinished event, TaskDef taskDef) {
    String testSuiteName = taskDef.fullyQualifiedName();
    Class<?> testClass = WeaverUtils.getClass(testSuiteName);
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    String testName = event.outcome().name();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories = Collections.emptyList();
    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    String testMethodName = null;
    Method testMethod = null;
    String testStatus = event.outcome().status().label();
    // todo: check throwable
    // Throwable throwable = event.outcome().cause().getOrElse(null);

    // Check if test was ignored, no test start
    if (testStatus.equals("ignored")) {
      eventHandler.onTestIgnore(
          testSuiteDescriptor,
          testDescriptor,
          testSuiteName,
          testName,
          TEST_FRAMEWORK,
          TEST_FRAMEWORK_VERSION,
          testParameters,
          categories,
          testClass,
          testMethodName,
          testMethod,
          null);
      return;
    }

    // Fake test start
    // todo: fake start time
    boolean isRetry = false;
    eventHandler.onTestStart(
        testSuiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        isRetry);

    // Proper end
    switch (testStatus) {
      case "success":
        eventHandler.onTestFinish(testDescriptor);
        break;
      case "cancelled":
      case "failure":
      case "exception":
        eventHandler.onTestFailure(testDescriptor, null);
        eventHandler.onTestFinish(testDescriptor);
        break;
    }
  }
}
