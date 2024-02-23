package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import datadog.trace.api.civisibility.coverage.CoverageBridge;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
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
  public void executionStarted(final TestDescriptor descriptor) {
    if (descriptor.isContainer()) {
      containerExecutionStarted(descriptor);
    } else if (descriptor.isTest()) {
      testCaseExecutionStarted(descriptor);
    }
  }

  @Override
  public void executionFinished(
      TestDescriptor descriptor, TestExecutionResult testExecutionResult) {
    if (descriptor.isContainer()) {
      containerExecutionFinished(descriptor, testExecutionResult);
    } else if (descriptor.isTest()) {
      testCaseExecutionFinished(descriptor, testExecutionResult);
    }
  }

  private void containerExecutionStarted(final TestDescriptor suiteDescriptor) {
    UniqueId uniqueId = suiteDescriptor.getUniqueId();
    if (!CucumberUtils.isFeature(uniqueId)) {
      return;
    }

    String testSuiteName = CucumberUtils.getFeatureName(suiteDescriptor);
    List<String> tags =
        suiteDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        suiteDescriptor,
        testSuiteName,
        testFramework,
        testFrameworkVersion,
        null,
        tags,
        false,
        TestFrameworkInstrumentation.CUCUMBER);
  }

  private void containerExecutionFinished(
      final TestDescriptor suiteDescriptor, final TestExecutionResult testExecutionResult) {
    if (!CucumberUtils.isFeature(suiteDescriptor.getUniqueId())) {
      return;
    }

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {
        String reason = throwable.getMessage();
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, reason);

        for (TestDescriptor child : suiteDescriptor.getChildren()) {
          executionSkipped(child, reason);
        }
      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(suiteDescriptor, throwable);
      }
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor);
  }

  private void testCaseExecutionStarted(final TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionStarted(testDescriptor, (ClasspathResourceSource) testSource);
    }
  }

  private void testResourceExecutionStarted(
      TestDescriptor testDescriptor, ClasspathResourceSource testSource) {
    TestDescriptor suiteDescriptor = CucumberUtils.getFeatureDescriptor(testDescriptor);
    String classpathResourceName = testSource.getClasspathResourceName();
    Pair<String, String> names =
        CucumberUtils.getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();
    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        suiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
        testFramework,
        testFrameworkVersion,
        null,
        tags,
        null,
        null,
        null,
        JUnitPlatformUtils.isRetry(testDescriptor));

    CoverageBridge.currentCoverageProbeStoreRecordNonCode(classpathResourceName);
  }

  private void testCaseExecutionFinished(
      final TestDescriptor testDescriptor, final TestExecutionResult testExecutionResult) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionFinished(testDescriptor, testExecutionResult);
    }
  }

  private void testResourceExecutionFinished(
      TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
            testDescriptor, throwable.getMessage());
      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, throwable);
      }
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(testDescriptor);
  }

  @Override
  public void executionSkipped(final TestDescriptor descriptor, final String reason) {
    TestSource testSource = descriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionSkipped(descriptor, (ClasspathResourceSource) testSource, reason);
    }
  }

  private void testResourceExecutionSkipped(
      TestDescriptor testDescriptor, ClasspathResourceSource testSource, String reason) {
    TestDescriptor suiteDescriptor = CucumberUtils.getFeatureDescriptor(testDescriptor);
    String classpathResourceName = testSource.getClasspathResourceName();
    Pair<String, String> names =
        CucumberUtils.getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();
    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        suiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
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
