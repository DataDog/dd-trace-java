package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.resource.ClassLoaders;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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

  private static final ClassLoader CUCUMBER_CLASS_LOADER = ClassLoaders.getDefaultClassLoader();
  public static final String FRAMEWORK_NAME = "cucumber";
  public static final String FRAMEWORK_VERSION = getVersion();

  private static String getVersion() {
    try (InputStream cucumberPropsStream =
        CUCUMBER_CLASS_LOADER.getResourceAsStream(
            "META-INF/maven/io.cucumber/cucumber-junit/pom.properties")) {
      Properties cucumberProps = new Properties();
      cucumberProps.load(cucumberPropsStream);
      String version = cucumberProps.getProperty("version");
      if (Strings.isNotBlank(version)) {
        return version;
      }
    } catch (Exception e) {
      // fallback below
    }
    return "unknown";
  }

  private static final MethodHandles REFLECTION = new MethodHandles(CUCUMBER_CLASS_LOADER);
  private static final MethodHandle PICKLE_ID_CONSTRUCTOR =
      REFLECTION.constructor("io.cucumber.junit.PickleRunners$PickleId", Pickle.class);
  private static final MethodHandle PICKLE_ID_URI_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.PickleRunners$PickleId", "uri");
  private static final MethodHandle FEATURE_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.FeatureRunner", "feature");

  private final Map<Object, Pickle> pickleById = new HashMap<>();

  public CucumberTracingListener(List<ParentRunner<?>> featureRunners) {
    for (ParentRunner<?> featureRunner : featureRunners) {
      Feature feature = (Feature) REFLECTION.invoke(FEATURE_GETTER, featureRunner);
      for (Pickle pickle : feature.getPickles()) {
        Object pickleId = REFLECTION.invoke(PICKLE_ID_CONSTRUCTOR, pickle);
        pickleById.put(pickleId, pickle);
      }
    }
  }

  @Override
  public void testSuiteStarted(final Description description) {
    if (isFeature(description)) {
      String testSuiteName = getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, FRAMEWORK_NAME, FRAMEWORK_VERSION, null, Collections.emptyList(), false);
    }
  }

  @Override
  public void testSuiteFinished(final Description description) {
    if (isFeature(description)) {
      String testSuiteName = getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
    }
  }

  @Override
  public void testStarted(final Description description) {
    String testSuiteName = getTestSuiteNameForScenario(description);
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

  private static String getTestSuiteNameForFeature(Description featureDescription) {
    Object uniqueId = JUnit4Utils.getUniqueId(featureDescription);
    return (uniqueId != null ? uniqueId + ":" : "") + featureDescription.getClassName();
  }

  private static String getTestSuiteNameForScenario(Description scenarioDescription) {
    URI featureUri = getFeatureUri(scenarioDescription);
    return (featureUri != null ? featureUri + ":" : "") + scenarioDescription.getClassName();
  }

  private static URI getFeatureUri(Description scenarioDescription) {
    try {
      Object pickleId = JUnit4Utils.getUniqueId(scenarioDescription);
      return REFLECTION.invoke(PICKLE_ID_URI_GETTER, pickleId);
    } catch (Exception e) {
      LOGGER.error(
          "Could not retrieve unique ID from scenario description {}", scenarioDescription, e);
      return null;
    }
  }

  private static void recordFeatureFileCodeCoverage(Description scenarioDescription) {
    try {
      Object pickleId = JUnit4Utils.getUniqueId(scenarioDescription);
      URI pickleUri = REFLECTION.invoke(PICKLE_ID_URI_GETTER, pickleId);
      String featureRelativePath = pickleUri.getSchemeSpecificPart();
      InstrumentationBridge.currentCoverageProbeStoreRecordNonCode(featureRelativePath);
    } catch (Exception e) {
      LOGGER.error("Could not record feature file coverage for {}", scenarioDescription, e);
    }
  }

  @Override
  public void testFinished(final Description description) {
    String testSuiteName = getTestSuiteNameForScenario(description);
    String testName = description.getMethodName();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, null, testName, null, null);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (isFeature(description)) {
      String testSuiteName = getTestSuiteNameForFeature(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
          testSuiteName, null, throwable);
    } else {
      String testSuiteName = getTestSuiteNameForScenario(description);
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
      String testSuiteName = getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);
    } else {
      String testSuiteName = getTestSuiteNameForScenario(description);
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
      String testSuiteName = getTestSuiteNameForFeature(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, FRAMEWORK_NAME, FRAMEWORK_VERSION, null, Collections.emptyList(), false);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
    } else {
      String testSuiteName = getTestSuiteNameForScenario(description);
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
