package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import datadog.trace.api.civisibility.coverage.CoverageBridge;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;

public class CucumberTracingListener implements EngineExecutionListener {

  private final String testFramework;
  private final String testFrameworkVersion;

  public CucumberTracingListener(TestEngine testEngine) {
    testFramework = testEngine.getId();
    testFrameworkVersion = CucumberUtils.getCucumberVersion(testEngine);
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
    UniqueId uniqueId = testDescriptor.getUniqueId();
    if (!CucumberUtils.isFeature(uniqueId)) {
      return;
    }

    String testSuiteName = CucumberUtils.getFeatureName(testDescriptor);
    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, null, tags, false);
  }

  private void containerExecutionFinished(
      final TestDescriptor testDescriptor, final TestExecutionResult testExecutionResult) {
    if (!CucumberUtils.isFeature(testDescriptor.getUniqueId())) {
      return;
    }

    String testSuiteName = CucumberUtils.getFeatureName(testDescriptor);
    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {

        String reason = throwable.getMessage();
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);

        for (TestDescriptor child : testDescriptor.getChildren()) {
          executionSkipped(child, reason);
        }

      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
            testSuiteName, null, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
  }

  private void testCaseExecutionStarted(final TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionStarted(testDescriptor, (ClasspathResourceSource) testSource);
    }
  }

  private void testResourceExecutionStarted(
      TestDescriptor testDescriptor, ClasspathResourceSource testSource) {
    String classpathResourceName = testSource.getClasspathResourceName();

    Pair<String, String> names =
        CucumberUtils.getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();

    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        null,
        tags,
        null,
        null,
        null);

    CoverageBridge.currentCoverageProbeStoreRecordNonCode(classpathResourceName);
  }

  private void testCaseExecutionFinished(
      final TestDescriptor testDescriptor, final TestExecutionResult testExecutionResult) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionFinished(
          testDescriptor, testExecutionResult, (ClasspathResourceSource) testSource);
    }
  }

  private void testResourceExecutionFinished(
      TestDescriptor testDescriptor,
      TestExecutionResult testExecutionResult,
      ClasspathResourceSource testSource) {
    String classpathResourceName = testSource.getClasspathResourceName();

    Pair<String, String> names =
        CucumberUtils.getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
            testSuiteName, null, testName, null, null, throwable.getMessage());
      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
            testSuiteName, null, testName, null, null, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, null, testName, null, null);
  }

  @Override
  public void executionSkipped(final TestDescriptor testDescriptor, final String reason) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionSkipped(testDescriptor, (ClasspathResourceSource) testSource, reason);
    }
  }

  private void testResourceExecutionSkipped(
      TestDescriptor testDescriptor, ClasspathResourceSource testSource, String reason) {
    String classpathResourceName = testSource.getClasspathResourceName();
    Pair<String, String> names =
        CucumberUtils.getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();

    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        null,
        tags,
        null,
        null,
        null,
        reason);
  }
}
