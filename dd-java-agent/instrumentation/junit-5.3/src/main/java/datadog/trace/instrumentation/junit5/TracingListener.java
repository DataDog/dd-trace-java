package datadog.trace.instrumentation.junit5;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

public class TracingListener implements EngineExecutionListener {

  private final String testFramework;
  private final String testFrameworkVersion;

  public TracingListener(TestEngine testEngine) {
    String engineId = testEngine.getId();
    testFramework = engineId != null && engineId.startsWith("junit") ? "junit5" : engineId;
    testFrameworkVersion = testEngine.getVersion().orElse(null);
  }

  @Override
  public void dynamicTestRegistered(TestDescriptor testDescriptor) {
    // no op
  }

  @Override
  public void reportingEntryPublished(TestDescriptor testDescriptor, ReportEntry entry) {
    // no op
  }

  @Override
  public void executionStarted(final TestDescriptor testDescriptor) {
    if (testDescriptor.isContainer()) {
      containerExecutionStarted(testDescriptor);
    } else if (testDescriptor.isTest()) {
      testCaseExecutionStarted(testDescriptor);
    }
  }

  @Override
  public void executionFinished(
      TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
    if (testDescriptor.isContainer()) {
      containerExecutionFinished(testDescriptor, testExecutionResult);
    } else if (testDescriptor.isTest()) {
      testCaseExecutionFinished(testDescriptor, testExecutionResult);
    }
  }

  private void containerExecutionStarted(final TestDescriptor testDescriptor) {
    if (!JUnitPlatformUtils.isSuite(testDescriptor)) {
      return;
    }

    Class<?> testClass = JUnitPlatformUtils.getJavaClass(testDescriptor);
    String testSuiteName =
        testClass != null ? testClass.getName() : testDescriptor.getLegacyReportingName();

    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, testClass, tags, false);
  }

  private void containerExecutionFinished(
      final TestDescriptor testDescriptor, final TestExecutionResult testExecutionResult) {
    if (!JUnitPlatformUtils.isSuite(testDescriptor)) {
      return;
    }

    Class<?> testClass = JUnitPlatformUtils.getJavaClass(testDescriptor);
    String testSuiteName =
        testClass != null ? testClass.getName() : testDescriptor.getLegacyReportingName();

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {

        String reason = throwable.getMessage();
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(
            testSuiteName, testClass, reason);

        for (TestDescriptor child : testDescriptor.getChildren()) {
          executionSkipped(child, reason);
        }

      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
            testSuiteName, testClass, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testClass);
  }

  private void testCaseExecutionStarted(final TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      testMethodExecutionStarted(testDescriptor, (MethodSource) testSource);
    }
  }

  private void testMethodExecutionStarted(TestDescriptor testDescriptor, MethodSource testSource) {
    String testSuitName = testSource.getClassName();
    String displayName = testDescriptor.getDisplayName();
    String testName = testSource.getMethodName();

    String testParameters = JUnitPlatformUtils.getParameters(testSource, displayName);
    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = JUnitPlatformUtils.getTestClass(testSource);
    Method testMethod = JUnitPlatformUtils.getTestMethod(testSource);
    String testMethodName = testSource.getMethodName();

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuitName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        testParameters,
        tags,
        testClass,
        testMethodName,
        testMethod);
  }

  private void testCaseExecutionFinished(
      final TestDescriptor testDescriptor, final TestExecutionResult testExecutionResult) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      testMethodExecutionFinished(testDescriptor, testExecutionResult, (MethodSource) testSource);
    }
  }

  private static void testMethodExecutionFinished(
      TestDescriptor testDescriptor,
      TestExecutionResult testExecutionResult,
      MethodSource testSource) {
    String testSuiteName = testSource.getClassName();
    Class<?> testClass = JUnitPlatformUtils.getTestClass(testSource);
    String displayName = testDescriptor.getDisplayName();
    String testName = testSource.getMethodName();
    String testParameters = JUnitPlatformUtils.getParameters(testSource, displayName);

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
            testSuiteName, testClass, testName, null, testParameters, throwable.getMessage());
      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
            testSuiteName, testClass, testName, null, testParameters, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, testClass, testName, null, testParameters);
  }

  @Override
  public void executionSkipped(final TestDescriptor testDescriptor, final String reason) {
    TestSource testSource = testDescriptor.getSource().orElse(null);

    if (testSource instanceof ClassSource) {
      // The annotation @Disabled is kept at type level.
      containerExecutionSkipped(testDescriptor, reason);

    } else if (testSource instanceof MethodSource) {
      // The annotation @Disabled is kept at method level.
      testMethodExecutionSkipped(testDescriptor, (MethodSource) testSource, reason);
    }
  }

  private void containerExecutionSkipped(final TestDescriptor testDescriptor, final String reason) {
    if (!JUnitPlatformUtils.isSuite(testDescriptor)) {
      return;
    }

    Class<?> testClass = JUnitPlatformUtils.getJavaClass(testDescriptor);
    String testSuiteName =
        testClass != null ? testClass.getName() : testDescriptor.getLegacyReportingName();

    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, testClass, tags, false);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, testClass, reason);

    for (TestDescriptor child : testDescriptor.getChildren()) {
      executionSkipped(child, reason);
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testClass);
  }

  private void testMethodExecutionSkipped(
      final TestDescriptor testDescriptor, final MethodSource methodSource, final String reason) {
    String testSuiteName = methodSource.getClassName();
    String displayName = testDescriptor.getDisplayName();
    String testName = methodSource.getMethodName();

    String testParameters = JUnitPlatformUtils.getParameters(methodSource, displayName);
    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = JUnitPlatformUtils.getTestClass(methodSource);
    Method testMethod = JUnitPlatformUtils.getTestMethod(methodSource);
    String testMethodName = methodSource.getMethodName();

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        testParameters,
        tags,
        testClass,
        testMethodName,
        testMethod,
        reason);
  }
}
