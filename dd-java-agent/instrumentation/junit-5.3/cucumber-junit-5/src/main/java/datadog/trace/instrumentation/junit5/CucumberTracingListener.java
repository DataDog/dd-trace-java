package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
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
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestSuiteStart(
            suiteDescriptor,
            testSuiteName,
            testFramework,
            testFrameworkVersion,
            null,
            tags,
            false,
            TestFrameworkInstrumentation.CUCUMBER,
            null);
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
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.CUCUMBER)
            .onTestSuiteSkip(suiteDescriptor, reason);

        for (TestDescriptor child : suiteDescriptor.getChildren()) {
          executionSkipped(child, reason);
        }
      } else {
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.CUCUMBER)
            .onTestSuiteFailure(suiteDescriptor, throwable);
      }
    }
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestSuiteFinish(suiteDescriptor, null);
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
    String testName = names.getRight();
    List<String> tags = JUnitPlatformUtils.getTags(testDescriptor);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestStart(
            suiteDescriptor,
            testDescriptor,
            testName,
            testFramework,
            testFrameworkVersion,
            null,
            tags,
            TestSourceData.UNKNOWN,
            null,
            TestEventsHandlerHolder.getExecutionHistory(testDescriptor));

    CoveragePerTestBridge.recordCoverage(classpathResourceName);
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
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.CUCUMBER)
            .onTestSkip(testDescriptor, throwable.getMessage());
      } else {
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.CUCUMBER)
            .onTestFailure(testDescriptor, throwable);
      }
    }
    TestExecutionHistory executionHistory =
        TestEventsHandlerHolder.getExecutionHistory(testDescriptor);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestFinish(testDescriptor, null, executionHistory);
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
    String testName = names.getRight();
    List<String> tags =
        testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestIgnore(
            suiteDescriptor,
            testDescriptor,
            testName,
            testFramework,
            testFrameworkVersion,
            null,
            tags,
            TestSourceData.UNKNOWN,
            reason,
            TestEventsHandlerHolder.getExecutionHistory(testDescriptor));
  }
}
