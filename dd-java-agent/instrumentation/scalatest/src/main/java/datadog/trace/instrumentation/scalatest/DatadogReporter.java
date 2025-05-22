package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.scalatest.execution.SuppressedTestFailedException;
import java.util.Collection;
import java.util.Collections;
import org.scalatest.events.Event;
import org.scalatest.events.RunAborted;
import org.scalatest.events.RunCompleted;
import org.scalatest.events.RunStarting;
import org.scalatest.events.RunStopped;
import org.scalatest.events.SuiteAborted;
import org.scalatest.events.SuiteCompleted;
import org.scalatest.events.SuiteStarting;
import org.scalatest.events.TestCanceled;
import org.scalatest.events.TestFailed;
import org.scalatest.events.TestIgnored;
import org.scalatest.events.TestPending;
import org.scalatest.events.TestStarting;
import org.scalatest.events.TestSucceeded;

public class DatadogReporter {

  private static final String TEST_FRAMEWORK = "scalatest";
  private static final String TEST_FRAMEWORK_VERSION = ScalatestUtils.getScalatestVersion();

  public static void handle(Event event) {
    if (event instanceof RunStarting) {
      start(event);

    } else if (event instanceof RunCompleted) {
      stop(event);

    } else if (event instanceof RunAborted) {
      stop(event);

    } else if (event instanceof RunStopped) {
      stop(event);

    } else if (event instanceof SuiteStarting) {
      onSuiteStart((SuiteStarting) event);

    } else if (event instanceof SuiteCompleted) {
      onSuiteFinish((SuiteCompleted) event);

    } else if (event instanceof SuiteAborted) {
      onSuiteAbort((SuiteAborted) event);

    } else if (event instanceof TestStarting) {
      onTestStart((TestStarting) event);

    } else if (event instanceof TestSucceeded) {
      onTestSuccess((TestSucceeded) event);

    } else if (event instanceof TestFailed) {
      onTestFailure((TestFailed) event);

    } else if (event instanceof TestIgnored) {
      onTestIgnore((TestIgnored) event);

    } else if (event instanceof TestCanceled) {
      onTestCancel((TestCanceled) event);

    } else if (event instanceof TestPending) {
      onTestPending((TestPending) event);
    }
  }

  private static void start(Event event) {
    int runStamp = event.ordinal().runStamp();
    RunContext.getOrCreate(runStamp);
  }

  private static void stop(Event event) {
    int runStamp = event.ordinal().runStamp();
    RunContext.destroy(runStamp);
  }

  private static void onSuiteStart(SuiteStarting event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Collection<String> categories = Collections.emptyList();
    boolean parallelized = true;

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestSuiteStart(
        new TestSuiteDescriptor(testSuiteName, testClass),
        testSuiteName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testClass,
        categories,
        parallelized,
        TestFrameworkInstrumentation.SCALATEST,
        null);
  }

  private static void onSuiteFinish(SuiteCompleted event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestSuiteFinish(new TestSuiteDescriptor(testSuiteName, testClass), null);
  }

  private static void onSuiteAbort(SuiteAborted event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Throwable throwable = event.throwable().getOrElse(null);

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestSuiteFailure(new TestSuiteDescriptor(testSuiteName, testClass), throwable);
    eventHandler.onTestSuiteFinish(new TestSuiteDescriptor(testSuiteName, testClass), null);
  }

  private static void onTestStart(TestStarting event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories;
    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    if (context.itrUnskippable(testIdentifier)) {
      categories = Collections.singletonList(CIConstants.Tags.ITR_UNSKIPPABLE_TAG);
    } else {
      categories = Collections.emptyList();
    }
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestStart(
        new TestSuiteDescriptor(testSuiteName, testClass),
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier),
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        new TestSourceData(testClass, null, null),
        null,
        context.getExecutionHistory(testIdentifier));
  }

  private static void onTestSuccess(TestSucceeded event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);

    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    TestExecutionHistory executionHistory = context.popExecutionHistory(testIdentifier);

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestFinish(testDescriptor, null, executionHistory);
  }

  private static void onTestFailure(TestFailed event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Throwable throwable = event.throwable().getOrElse(null);
    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);

    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    TestExecutionHistory executionHistory = context.popExecutionHistory(testIdentifier);

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestFailure(testDescriptor, throwable);
    eventHandler.onTestFinish(testDescriptor, null, executionHistory);
  }

  private static void onTestIgnore(TestIgnored event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories = Collections.emptyList();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());

    TestIdentifier skippableTest = new TestIdentifier(testSuiteName, testName, null);
    SkipReason reason = context.getSkipReason(skippableTest);

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestIgnore(
        new TestSuiteDescriptor(testSuiteName, testClass),
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier),
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        new TestSourceData(testClass, null, null),
        reason != null ? reason.getDescription() : null,
        context.popExecutionHistory(skippableTest));
  }

  private static void onTestCancel(TestCanceled event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Throwable throwable = event.throwable().getOrElse(null);
    String reason = throwable != null ? throwable.getMessage() : null;

    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    if (throwable instanceof SuppressedTestFailedException) {
      eventHandler.onTestFailure(testDescriptor, throwable.getCause());
    } else {
      eventHandler.onTestSkip(testDescriptor, reason);
    }

    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    TestExecutionHistory executionHistory = context.popExecutionHistory(testIdentifier);

    eventHandler.onTestFinish(testDescriptor, null, executionHistory);
  }

  private static void onTestPending(TestPending event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.get(runStamp);
    if (context == null) {
      return;
    }

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String reason = "pending";

    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();
    eventHandler.onTestSkip(testDescriptor, reason);

    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    TestExecutionHistory executionHistory = context.popExecutionHistory(testIdentifier);

    eventHandler.onTestFinish(testDescriptor, null, executionHistory);
  }
}
