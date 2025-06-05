package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import io.cucumber.core.gherkin.Pickle;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.ParentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunListener.ThreadSafe
public class CucumberTracingListener extends TracingListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(CucumberTracingListener.class);

  public static final String FRAMEWORK_NAME = "cucumber";
  public static final String FRAMEWORK_VERSION = CucumberUtils.getVersion();

  private final ContextStore<Description, TestExecutionHistory> executionHistories;
  private final Map<Object, Pickle> pickleById;

  public CucumberTracingListener(
      ContextStore<Description, TestExecutionHistory> executionHistories,
      List<ParentRunner<?>> featureRunners) {
    this.executionHistories = executionHistories;
    pickleById = CucumberUtils.getPicklesById(featureRunners);
  }

  @Override
  public void testSuiteStarted(final Description description) {
    if (isFeature(description)) {
      TestSuiteDescriptor suiteDescriptor = CucumberUtils.toSuiteDescriptor(description);
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteStart(
              suiteDescriptor,
              testSuiteName,
              FRAMEWORK_NAME,
              FRAMEWORK_VERSION,
              null,
              Collections.emptyList(),
              false,
              TestFrameworkInstrumentation.CUCUMBER,
              null);
    }
  }

  @Override
  public void testSuiteFinished(final Description description) {
    if (isFeature(description)) {
      TestSuiteDescriptor suiteDescriptor = CucumberUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteFinish(suiteDescriptor, null);
    }
  }

  @Override
  public void testStarted(final Description description) {
    String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
    String testName = CucumberUtils.getTestNameForScenario(description);
    List<String> categories = getCategories(description);

    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestStart(
            new TestSuiteDescriptor(testSuiteName, null),
            CucumberUtils.toTestDescriptor(description),
            testName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            null,
            categories,
            TestSourceData.UNKNOWN,
            null,
            executionHistories.get(description));

    recordFeatureFileCodeCoverage(description);
  }

  private static void recordFeatureFileCodeCoverage(Description scenarioDescription) {
    try {
      URI pickleUri = CucumberUtils.getPickleUri(scenarioDescription);
      String featureRelativePath = pickleUri.getSchemeSpecificPart();
      CoveragePerTestBridge.recordCoverage(featureRelativePath);
    } catch (Exception e) {
      LOGGER.error("Could not record feature file coverage for {}", scenarioDescription, e);
    }
  }

  @Override
  public void testFinished(final Description description) {
    TestDescriptor testDescriptor = CucumberUtils.toTestDescriptor(description);
    TestExecutionHistory executionHistory = executionHistories.get(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.CUCUMBER)
        .onTestFinish(testDescriptor, null, executionHistory);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (isFeature(description)) {
      TestSuiteDescriptor suiteDescriptor = CucumberUtils.toSuiteDescriptor(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteFailure(suiteDescriptor, throwable);
    } else {
      TestDescriptor testDescriptor = CucumberUtils.toTestDescriptor(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestFailure(testDescriptor, throwable);
    }
  }

  @Override
  public void testAssumptionFailure(final Failure failure) {
    String reason;
    Throwable throwable = failure.getException();
    if (throwable != null) {
      reason = throwable.getMessage();
    } else {
      reason = null;
    }

    Description description = failure.getDescription();
    if (isFeature(description)) {
      TestSuiteDescriptor suiteDescriptor = CucumberUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteSkip(suiteDescriptor, reason);
    } else {
      TestDescriptor testDescriptor = CucumberUtils.toTestDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSkip(testDescriptor, reason);
    }
  }

  @Override
  public void testIgnored(final Description description) {
    Ignore ignore = description.getAnnotation(Ignore.class);
    String reason = ignore != null ? ignore.value() : null;

    if (isFeature(description)) {
      TestSuiteDescriptor suiteDescriptor = CucumberUtils.toSuiteDescriptor(description);
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteStart(
              suiteDescriptor,
              testSuiteName,
              FRAMEWORK_NAME,
              FRAMEWORK_VERSION,
              null,
              Collections.emptyList(),
              false,
              TestFrameworkInstrumentation.CUCUMBER,
              null);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteSkip(suiteDescriptor, reason);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestSuiteFinish(suiteDescriptor, null);
    } else {
      String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
      String testName = CucumberUtils.getTestNameForScenario(description);
      List<String> categories = getCategories(description);

      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.CUCUMBER)
          .onTestIgnore(
              new TestSuiteDescriptor(testSuiteName, null),
              CucumberUtils.toTestDescriptor(description),
              testName,
              FRAMEWORK_NAME,
              FRAMEWORK_VERSION,
              null,
              categories,
              TestSourceData.UNKNOWN,
              reason,
              executionHistories.get(description));
    }
  }

  private static boolean isFeature(final Description description) {
    Object uniqueId = JUnit4Utils.getUniqueId(description);
    return uniqueId != null && uniqueId.toString().endsWith(".feature");
  }

  private List<String> getCategories(Description description) {
    Pickle pickle = pickleById.get(JUnit4Utils.getUniqueId(description));
    return CucumberUtils.getCategories(pickle);
  }
}
