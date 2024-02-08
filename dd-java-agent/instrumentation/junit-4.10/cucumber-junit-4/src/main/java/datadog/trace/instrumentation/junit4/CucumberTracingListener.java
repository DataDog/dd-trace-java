package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.coverage.CoverageBridge;
import io.cucumber.core.gherkin.Pickle;
import java.net.URI;
import java.util.ArrayList;
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

  private final Map<Object, Pickle> pickleById;

  public CucumberTracingListener(List<ParentRunner<?>> featureRunners) {
    pickleById = CucumberUtils.getPicklesById(featureRunners);
  }

  @Override
  public void testSuiteStarted(final Description description) {
    if (isFeature(description)) {
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, FRAMEWORK_NAME, FRAMEWORK_VERSION, null, Collections.emptyList(), false);
    }
  }

  @Override
  public void testSuiteFinished(final Description description) {
    if (isFeature(description)) {
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
    }
  }

  @Override
  public void testStarted(final Description description) {
    String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
    String testName = description.getMethodName();
    List<String> categories = getCategories(description);

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuiteName,
        testName,
        null,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        categories,
        null,
        null,
        null);

    recordFeatureFileCodeCoverage(description);
  }

  private static void recordFeatureFileCodeCoverage(Description scenarioDescription) {
    try {
      URI pickleUri = CucumberUtils.getPickleUri(scenarioDescription);
      String featureRelativePath = pickleUri.getSchemeSpecificPart();
      CoverageBridge.currentCoverageProbeStoreRecordNonCode(featureRelativePath);
    } catch (Exception e) {
      LOGGER.error("Could not record feature file coverage for {}", scenarioDescription, e);
    }
  }

  @Override
  public void testFinished(final Description description) {
    String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
    String testName = description.getMethodName();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, null, testName, null, null);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (isFeature(description)) {
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
          testSuiteName, null, throwable);
    } else {
      String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
      String testName = description.getMethodName();
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
          testSuiteName, null, testName, null, null, throwable);
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
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);
    } else {
      String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
      String testName = description.getMethodName();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
          testSuiteName, null, testName, null, null, reason);
    }
  }

  @Override
  public void testIgnored(final Description description) {
    Ignore ignore = description.getAnnotation(Ignore.class);
    String reason = ignore != null ? ignore.value() : null;

    if (isFeature(description)) {
      String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, FRAMEWORK_NAME, FRAMEWORK_VERSION, null, Collections.emptyList(), false);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
    } else {
      String testSuiteName = CucumberUtils.getTestSuiteNameForScenario(description);
      String testName = description.getMethodName();
      List<String> categories = getCategories(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
          testSuiteName,
          testName,
          null,
          FRAMEWORK_NAME,
          FRAMEWORK_VERSION,
          null,
          categories,
          null,
          null,
          null,
          reason);
    }
  }

  private static boolean isFeature(final Description description) {
    Object uniqueId = JUnit4Utils.getUniqueId(description);
    return uniqueId != null && uniqueId.toString().endsWith(".feature");
  }

  private List<String> getCategories(Description description) {
    Pickle pickle = pickleById.get(JUnit4Utils.getUniqueId(description));
    List<String> pickleTags = pickle.getTags();
    List<String> categories = new ArrayList<>(pickleTags.size());
    for (String tag : pickleTags) {
      categories.add(tag.substring(1)); // remove leading "@"
    }
    return categories;
  }
}
