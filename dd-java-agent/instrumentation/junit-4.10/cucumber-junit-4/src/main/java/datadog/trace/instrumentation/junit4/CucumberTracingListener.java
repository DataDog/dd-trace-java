package datadog.trace.instrumentation.junit4;

import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.resource.ClassLoaders;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
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

@RunListener.ThreadSafe
public class CucumberTracingListener extends TracingListener {

  public static final String FRAMEWORK_NAME = "cucumber";
  public static final String FRAMEWORK_VERSION = getVersion();

  private static String getVersion() {
    ClassLoader cucumberClassLoader = ClassLoaders.getDefaultClassLoader();
    try (InputStream cucumberPropsStream =
        cucumberClassLoader.getResourceAsStream(
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

  private static final MethodHandles REFLECTION =
      new MethodHandles(ClassLoaders.getDefaultClassLoader());
  private static final MethodHandle PICKLE_ID_CONSTRUCTOR =
      REFLECTION.constructor("io.cucumber.junit.PickleRunners$PickleId", Pickle.class);

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
      String testSuiteName = description.getClassName();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, FRAMEWORK_NAME, FRAMEWORK_VERSION, null, Collections.emptyList(), false);
    }
  }

  @Override
  public void testSuiteFinished(final Description description) {
    if (isFeature(description)) {
      String testSuiteName = description.getClassName();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
    }
  }

  @Override
  public void testStarted(final Description description) {
    String testSuiteName = description.getClassName();
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
  }

  @Override
  public void testFinished(final Description description) {
    String testSuiteName = description.getClassName();
    String testName = description.getMethodName();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, null, testName, null, null);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (isFeature(description)) {
      String testSuiteName = description.getClassName();
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
          testSuiteName, null, throwable);
    } else {
      String testSuiteName = description.getClassName();
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
      String testSuiteName = description.getClassName();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);
    } else {
      String testSuiteName = description.getClassName();
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
      String testSuiteName = description.getClassName();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, FRAMEWORK_NAME, FRAMEWORK_VERSION, null, Collections.emptyList(), false);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, null, reason);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, null);
    } else {
      String testSuiteName = description.getClassName();
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
