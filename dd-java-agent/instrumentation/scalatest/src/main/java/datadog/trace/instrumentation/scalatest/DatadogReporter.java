package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
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
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Collection<String> categories = Collections.emptyList();
    boolean parallelized = true;

    eventHandler.onTestSuiteStart(
        testSuiteName, TEST_FRAMEWORK, TEST_FRAMEWORK_VERSION, testClass, categories, parallelized);
  }

  private static void onSuiteFinish(SuiteCompleted event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    eventHandler.onTestSuiteFinish(testSuiteName, testClass);
  }

  private static void onSuiteAbort(SuiteAborted event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Throwable throwable = event.throwable().getOrElse(null);
    eventHandler.onTestSuiteFailure(testSuiteName, testClass, throwable);
    eventHandler.onTestSuiteFinish(testSuiteName, testClass);
  }

  private static void onTestStart(TestStarting event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories;
    if (context.unskippable(new TestIdentifier(testSuiteName, testName, null, null))) {
      categories = Collections.singletonList(InstrumentationBridge.ITR_UNSKIPPABLE_TAG);
    } else {
      categories = Collections.emptyList();
    }
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testMethodName = null;
    Method testMethod = null;

    eventHandler.onTestStart(
        testSuiteName,
        testName,
        testQualifier,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod);
  }

  private static void onTestSuccess(TestSucceeded event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    eventHandler.onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  private static void onTestFailure(TestFailed event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Throwable throwable = event.throwable().getOrElse(null);
    eventHandler.onTestFailure(
        testSuiteName, testClass, testName, testQualifier, testParameters, throwable);
    eventHandler.onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  private static void onTestIgnore(TestIgnored event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Collection<String> categories = Collections.emptyList();
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String testMethodName = null;
    Method testMethod = null;

    String reason;
    TestIdentifier skippableTest = new TestIdentifier(testSuiteName, testName, null, null);
    if (context.skipped(skippableTest)) {
      reason = InstrumentationBridge.ITR_SKIP_REASON;
    } else {
      reason = null;
    }

    eventHandler.onTestIgnore(
        testSuiteName,
        testName,
        testQualifier,
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
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    Throwable throwable = event.throwable().getOrElse(null);
    String reason = throwable != null ? throwable.getMessage() : null;

    if (throwable instanceof SuppressedTestFailedException) {
      eventHandler.onTestFailure(
          testSuiteName, testClass, testName, testQualifier, testParameters, throwable.getCause());
    } else {
      eventHandler.onTestSkip(
          testSuiteName, testClass, testName, testQualifier, testParameters, reason);
    }

    eventHandler.onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  private static void onTestPending(TestPending event) {
    int runStamp = event.ordinal().runStamp();
    RunContext context = RunContext.getOrCreate(runStamp);
    TestEventsHandler eventHandler = context.getEventHandler();

    String testSuiteName = event.suiteId();
    String testName = event.testName();
    Object testQualifier = null;
    String testParameters = null;
    Class<?> testClass = ScalatestUtils.getClass(event.suiteClassName());
    String reason = "pending";

    eventHandler.onTestSkip(
        testSuiteName, testClass, testName, testQualifier, testParameters, reason);
    eventHandler.onTestFinish(testSuiteName, testClass, testName, testQualifier, testParameters);
  }
}
