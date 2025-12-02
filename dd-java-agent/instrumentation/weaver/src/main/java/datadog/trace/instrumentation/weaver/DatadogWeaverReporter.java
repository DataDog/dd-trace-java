package datadog.trace.instrumentation.weaver;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.api.time.SystemTimeSource;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import sbt.testing.TaskDef;
import scala.Option;
import weaver.Result;
import weaver.TestOutcome;
import weaver.framework.RunEvent;
import weaver.framework.SuiteFinished;
import weaver.framework.SuiteStarted;
import weaver.framework.TestFinished;

public class DatadogWeaverReporter {

  private static final String TEST_FRAMEWORK = "weaver";
  private static final String TEST_FRAMEWORK_VERSION = WeaverUtils.getWeaverVersion();

  private static volatile TestEventsHandler<TestSuiteDescriptor, TestDescriptor>
      TEST_EVENTS_HANDLER;

  public static synchronized void start() {
    if (TEST_EVENTS_HANDLER == null) {
      TEST_EVENTS_HANDLER =
          InstrumentationBridge.createTestEventsHandler(
              "weaver", null, null, WeaverUtils.CAPABILITIES);
    }
  }

  /** Used by instrumentation tests */
  public static synchronized void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  public static void processEvent(Object event, TaskDef taskDef) {
    if (event instanceof RunEvent) {
      // handle event here, using taskDef reference to get suite details
      if (event instanceof SuiteStarted) {
        onSuiteStart((SuiteStarted) event);
      } else if (event instanceof SuiteFinished) {
        onSuiteFinish((SuiteFinished) event);
      } else if (event instanceof TestFinished) {
        onTestFinished((TestFinished) event, taskDef);
      }
    }
  }

  public static void onSuiteStart(SuiteStarted event) {
    String testSuiteName = event.name();
    Class<?> testClass = WeaverUtils.getClass(testSuiteName);
    Collection<String> categories = Collections.emptyList();
    boolean parallelized = true;

    TEST_EVENTS_HANDLER.onTestSuiteStart(
        new TestSuiteDescriptor(testSuiteName, testClass),
        testSuiteName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testClass,
        categories,
        parallelized,
        TestFrameworkInstrumentation.WEAVER,
        null);
  }

  public static void onSuiteFinish(SuiteFinished event) {
    String testSuiteName = event.name();
    Class<?> testClass = WeaverUtils.getClass(testSuiteName);

    TEST_EVENTS_HANDLER.onTestSuiteFinish(new TestSuiteDescriptor(testSuiteName, testClass), null);
  }

  public static void onTestFinished(TestFinished event, TaskDef taskDef) {
    if (!(event.outcome() instanceof TestOutcome.Default)) {
      // Cannot obtain desired information without the TestOutcome.Default fields
      return;
    }

    TestOutcome.Default testOutcome = (TestOutcome.Default) event.outcome();
    String testSuiteName = taskDef.fullyQualifiedName();
    Class<?> testClass = WeaverUtils.getClass(testSuiteName);
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    String testName = event.outcome().name();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories = Collections.emptyList();
    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    String testMethodName = null;
    Method testMethod = null;
    TestExecutionHistory executionHistory = null;

    // Only test finish is reported, so fake test start timestamp
    long endMicros = SystemTimeSource.INSTANCE.getCurrentTimeMicros();
    long startMicros = endMicros - testOutcome.duration().toMicros();
    TEST_EVENTS_HANDLER.onTestStart(
        testSuiteDescriptor,
        testDescriptor,
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        new TestSourceData(testClass, testMethod, testMethodName),
        startMicros,
        executionHistory);

    if (testOutcome.result() != null) {
      // Failed outcomes
      if (WeaverUtils.isResultFailure(testOutcome.result())) {
        Throwable throwable =
            WeaverUtils.unwrap(
                WeaverUtils.METHOD_HANDLES.invoke(
                    WeaverUtils.GET_FAILURE_SOURCE_HANDLE, testOutcome.result()),
                Throwable.class);
        TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, throwable);
      } else if (testOutcome.result() instanceof Result.Failures) {
        Result.Failures result = (Result.Failures) testOutcome.result();
        Object headFailure = result.failures().head();
        Throwable throwable =
            WeaverUtils.unwrap(
                WeaverUtils.METHOD_HANDLES.invoke(
                    WeaverUtils.GET_FAILURE_SOURCE_HANDLE, headFailure),
                Throwable.class);
        TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, throwable);
      } else if (testOutcome.result() instanceof Result.Exception) {
        Result.Exception result = (Result.Exception) testOutcome.result();
        Throwable throwable = result.source();
        TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, throwable);

        // Skipped outcomes
      } else if (testOutcome.result() instanceof Result.Ignored) {
        Result.Ignored result = (Result.Ignored) testOutcome.result();
        String reason =
            WeaverUtils.unwrap(
                WeaverUtils.METHOD_HANDLES.invoke(
                    WeaverUtils.GET_IGNORED_REASON_HANDLE, testOutcome.result()),
                String.class);
        TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, reason);
      } else if (WeaverUtils.isResultCancelled(testOutcome.result())) {
        Option<String> reason =
            WeaverUtils.METHOD_HANDLES.invoke(
                WeaverUtils.GET_CANCELLED_REASON_HANDLE, testOutcome.result());
        TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, reason.getOrElse(null));
      }
    }

    TEST_EVENTS_HANDLER.onTestFinish(testDescriptor, endMicros, executionHistory);
  }
}
