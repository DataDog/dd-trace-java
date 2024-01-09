package datadog.trace.instrumentation.junit4;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.resource.ClassLoaders;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.net.URI;
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

  private static final MethodHandles REFLECTION = new MethodHandles(CUCUMBER_CLASS_LOADER);
  private static final MethodHandle FEATURE_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.FeatureRunner", "feature");
  private static final MethodHandle PICKLE_ID_CONSTRUCTOR =
      REFLECTION.constructor("io.cucumber.junit.PickleRunners$PickleId", Pickle.class);
  private static final MethodHandle PICKLE_ID_URI_GETTER =
      REFLECTION.privateFieldGetter("io.cucumber.junit.PickleRunners$PickleId", "uri");
  private static final MethodHandle PICKLE_RUNNER_GET_DESCRIPTION =
      REFLECTION.method("io.cucumber.junit.PickleRunners$PickleRunner", "getDescription");

  private CucumberUtils() {}

  public static Map<Object, Pickle> getPicklesById(List<ParentRunner<?>> featureRunners) {
    Map<Object, Pickle> pickleById = new HashMap<>();
    for (ParentRunner<?> featureRunner : featureRunners) {
      Feature feature = (Feature) REFLECTION.invoke(FEATURE_GETTER, featureRunner);
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

  public static String getTestSuiteNameForFeature(Description featureDescription) {
    Object uniqueId = JUnit4Utils.getUniqueId(featureDescription);
    return (uniqueId != null ? uniqueId + ":" : "") + featureDescription.getClassName();
  }

  public static String getTestSuiteNameForScenario(Description scenarioDescription) {
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

  public static Description getPickleRunnerDescription(
      Object /* io.cucumber.junit.PickleRunners.PickleRunner */ runner) {
    return REFLECTION.invoke(PICKLE_RUNNER_GET_DESCRIPTION, runner);
  }

  public static TestIdentifier toTestIdentifier(Description description) {
    String suite = getTestSuiteNameForScenario(description);
    String name = description.getMethodName();
    return new TestIdentifier(suite, name, null, null);
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
