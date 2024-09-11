package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.scalatest.retry.SuppressedTestFailedException;
import java.lang.reflect.Method;
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
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
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
        TestFrameworkInstrumentation.SCALATEST);
  }

  private static void onSuiteFinish(SuiteCompleted event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    eventHandler.onTestSuiteFinish(new TestSuiteDescriptor(testSuiteName, testClass));
  }

  private static void onSuiteAbort(SuiteAborted event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Throwable throwable = event.throwable().getOrElse(null);
    eventHandler.onTestSuiteFailure(new TestSuiteDescriptor(testSuiteName, testClass), throwable);
    eventHandler.onTestSuiteFinish(new TestSuiteDescriptor(testSuiteName, testClass));
  }

  private static void onTestStart(TestStarting event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories;
    TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null);
    if (context.unskippable(testIdentifier)) {
      categories = Collections.singletonList(InstrumentationBridge.ITR_UNSKIPPABLE_TAG);
    } else {
      categories = Collections.emptyList();
    }
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testMethodName = null;
    Method testMethod = null;
    TestRetryPolicy retryPolicy = context.popRetryPolicy(testIdentifier);

    eventHandler.onTestStart(
        new TestSuiteDescriptor(testSuiteName, testClass),
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier),
        testSuiteName,
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        retryPolicy != null && retryPolicy.currentExecutionIsRetry());
  }

  private static void onTestSuccess(TestSucceeded event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    eventHandler.onTestFinish(testDescriptor);
  }

  private static void onTestFailure(TestFailed event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Throwable throwable = event.throwable().getOrElse(null);
    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    eventHandler.onTestFailure(testDescriptor, throwable);
    eventHandler.onTestFinish(testDescriptor);
  }

  private static void onTestIgnore(TestIgnored event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories = Collections.emptyList();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testMethodName = null;
    Method testMethod = null;

    String reason;
    TestIdentifier skippableTest = new TestIdentifier(testSuiteName, testName, null);
    if (context.skipped(skippableTest)) {
      reason = InstrumentationBridge.ITR_SKIP_REASON;
    } else {
      reason = null;
    }

    eventHandler.onTestIgnore(
        new TestSuiteDescriptor(testSuiteName, testClass),
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier),
        testSuiteName,
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        reason);
  }

  private static void onTestCancel(TestCanceled event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Throwable throwable = event.throwable().getOrElse(null);
    String reason = throwable != null ? throwable.getMessage() : null;

    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    if (throwable instanceof SuppressedTestFailedException) {
      eventHandler.onTestFailure(testDescriptor, throwable.getCause());
    } else {
      eventHandler.onTestSkip(testDescriptor, reason);
    }
    eventHandler.onTestFinish(testDescriptor);
  }

  private static void onTestPending(TestPending event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String reason = "pending";

    TestDescriptor testDescriptor =
        new TestDescriptor(testSuiteName, testClass, testName, testParameters, testQualifier);
    eventHandler.onTestSkip(testDescriptor, reason);
    eventHandler.onTestFinish(testDescriptor);
  }
}
