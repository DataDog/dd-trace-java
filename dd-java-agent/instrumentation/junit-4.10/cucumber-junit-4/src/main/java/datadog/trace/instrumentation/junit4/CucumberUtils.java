package datadog.trace.instrumentation.junit4;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.resource.ClassLoaders;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.runner.Description;
import org.junit.runners.ParentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CucumberUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(CucumberUtils.class);

  private static final ClassLoader CUCUMBER_CLASS_LOADER = ClassLoaders.getDefaultClassLoader();

  public static String getVersion() {
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

  private static final String NO_STEP_PICKLE_RUNNER_CLASSNAME =
      "io.cucumber.junit.PickleRunners$NoStepDescriptions";
  private static final String WITH_STEP_PICKLE_RUNNER_CLASSNAME =
      "io.cucumber.junit.PickleRunners$WithStepDescriptions";

  private static final MethodHandles REFLECTION = new MethodHandles(CUCUMBER_CLASS_LOADER);
  private static final MethodHandle FEATURE_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.FeatureRunner", "feature");
  private static final MethodHandle PICKLE_ID_CONSTRUCTOR =
      REFLECTION.constructor("io.cucumber.junit.PickleRunners$PickleId", Pickle.class);
  private static final MethodHandle PICKLE_ID_URI_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.PickleRunners$PickleId", "uri");
  private static final MethodHandle PICKLE_ID_LINE_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.PickleRunners$PickleId", "pickleLine");
  private static final MethodHandle PICKLE_RUNNER_GET_DESCRIPTION =
      REFLECTION.method("io.cucumber.junit.PickleRunners$PickleRunner", "getDescription");
  private static final MethodHandle PICKLE_RUNNER_NO_STEP_GET_PICKLE =
      REFLECTION.privateFieldGetter(NO_STEP_PICKLE_RUNNER_CLASSNAME, "pickle");
  private static final MethodHandle PICKLE_RUNNER_WITH_STEP_GET_PICKLE =
      REFLECTION.privateFieldGetter(WITH_STEP_PICKLE_RUNNER_CLASSNAME, "pickle");

  public static final List<LibraryCapability> CAPABILITIES =
      Arrays.asList(
          LibraryCapability.TIA,
          LibraryCapability.ATR,
          LibraryCapability.EFD,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.DISABLED,
          LibraryCapability.ATTEMPT_TO_FIX);

  private CucumberUtils() {}

  public static Map<Object, Pickle> getPicklesById(List<ParentRunner<?>> featureRunners) {
    Map<Object, Pickle> pickleById = new HashMap<>();
    for (ParentRunner<?> featureRunner : featureRunners) {
      Feature feature = REFLECTION.invoke(FEATURE_GETTER, featureRunner);
      for (Pickle pickle : feature.getPickles()) {
        Object pickleId = REFLECTION.invoke(PICKLE_ID_CONSTRUCTOR, pickle);
        pickleById.put(pickleId, pickle);
      }
    }
    return pickleById;
  }

  public static URI getPickleUri(Description scenarioDescription) {
    Object pickleId = JUnit4Utils.getUniqueId(scenarioDescription);
    return REFLECTION.invoke(PICKLE_ID_URI_GETTER, pickleId);
  }

  public static Integer getPickleLine(Description scenarioDescription) {
    Object pickleId = JUnit4Utils.getUniqueId(scenarioDescription);
    return REFLECTION.invoke(PICKLE_ID_LINE_GETTER, pickleId);
  }

  public static String getTestSuiteNameForFeature(Description featureDescription) {
    Object uniqueId = JUnit4Utils.getUniqueId(featureDescription);
    return (uniqueId != null ? uniqueId + ":" : "") + featureDescription.getClassName();
  }

  public static String getTestSuiteNameForScenario(Description scenarioDescription) {
    URI featureUri = getFeatureUri(scenarioDescription);
    return (featureUri != null ? featureUri + ":" : "")
        + getFeatureNameForScenario(scenarioDescription);
  }

  private static String getFeatureNameForScenario(Description scenarioDescription) {
    String scenarioDescriptionString = scenarioDescription.toString();
    int featureNameStart = getFeatureNameStartIdx(scenarioDescriptionString);
    if (featureNameStart >= 0) {
      int descriptionLength = scenarioDescriptionString.length();
      // feature name is wrapped in brackets, hence the +1/-1
      return scenarioDescriptionString.substring(featureNameStart + 1, descriptionLength - 1);
    } else {
      // fallback to default method
      return scenarioDescription.getClassName();
    }
  }

  public static String getTestNameForScenario(Description scenarioDescription) {
    String scenarioDescriptionString = scenarioDescription.toString();
    int featureNameStart = getFeatureNameStartIdx(scenarioDescriptionString);
    if (featureNameStart > 0) { // if featureNameStart == 0, then test name is empty and of no use
      return scenarioDescriptionString.substring(0, featureNameStart);
    }

    // fallback to default method
    String methodName = scenarioDescription.getMethodName();
    if (Strings.isNotBlank(methodName)) {
      return methodName;
    }

    Integer pickleLine = getPickleLine(scenarioDescription);
    if (pickleLine != null) {
      return "LINE:" + pickleLine;
    }

    return "EMPTY_NAME";
  }

  /**
   * JUnit 4 expects description string to have the form {@code "Scenario Name(Feature Name)"} The
   * standard {@link Description#getClassName()} and {@link Description#getMethodName()} methods use
   * a regex to split the name parts. This does not work correctly when feature or scenario names
   * have bracket characters in them.
   */
  private static int getFeatureNameStartIdx(String scenarioDescriptionString) {
    int openBrackets = 0;
    for (int i = scenarioDescriptionString.length() - 1; i >= 0; i--) {
      char c = scenarioDescriptionString.charAt(i);
      if (c == ')') {
        openBrackets++;
      } else if (c == '(') {
        if (--openBrackets == 0) {
          return i;
        }
      }
    }
    return -1;
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

  public static Description getPickleRunnerDescription(
      Object /* io.cucumber.junit.PickleRunners.PickleRunner */ runner) {
    return REFLECTION.invoke(PICKLE_RUNNER_GET_DESCRIPTION, runner);
  }

  public static TestIdentifier toTestIdentifier(Description description) {
    String suite = getTestSuiteNameForScenario(description);
    String name = getTestNameForScenario(description);
    return new TestIdentifier(suite, name, null);
  }

  public static TestDescriptor toTestDescriptor(Description description) {
    String suite = getTestSuiteNameForScenario(description);
    String name = getTestNameForScenario(description);
    return new TestDescriptor(suite, null, name, null, null);
  }

  public static TestSuiteDescriptor toSuiteDescriptor(Description description) {
    String testSuiteName = CucumberUtils.getTestSuiteNameForFeature(description);
    return new TestSuiteDescriptor(testSuiteName, null);
  }

  public static Collection<String> getPickleRunnerTags(Object pickleRunner) {
    Pickle pickle = getPickle(pickleRunner);
    return getCategories(pickle);
  }

  private static Pickle getPickle(Object pickleRunner) {
    try {
      if (pickleRunner.getClass().getName().equals(NO_STEP_PICKLE_RUNNER_CLASSNAME)) {
        return REFLECTION.invoke(PICKLE_RUNNER_NO_STEP_GET_PICKLE, pickleRunner);
      } else {
        return REFLECTION.invoke(PICKLE_RUNNER_WITH_STEP_GET_PICKLE, pickleRunner);
      }
    } catch (Exception e) {
      return null;
    }
  }

  public static List<String> getCategories(Pickle pickle) {
    List<String> pickleTags = pickle.getTags();
    List<String> categories = new ArrayList<>(pickleTags.size());
    for (String tag : pickleTags) {
      categories.add(tag.substring(1)); // remove leading "@"
    }
    return categories;
  }

  public static final class MuzzleHelper {
    public static Reference[] additionalMuzzleReferences() {
      return new Reference[] {
        new Reference.Builder("io.cucumber.junit.FeatureRunner")
            .withField(new String[0], 0, "feature", "Lio/cucumber/core/gherkin/Feature;")
            .build(),
        new Reference.Builder("io.cucumber.junit.PickleRunners$PickleId")
            .withField(new String[0], 0, "uri", "Ljava/net/URI;")
            .build(),
        new Reference.Builder("io.cucumber.junit.PickleRunners$PickleRunner")
            .withMethod(new String[0], 0, "getDescription", "Lorg/junit/runner/Description;")
            .build()
      };
    }
  }
}
