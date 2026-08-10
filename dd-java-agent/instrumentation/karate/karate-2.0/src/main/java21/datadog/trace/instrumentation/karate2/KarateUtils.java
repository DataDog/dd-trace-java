package datadog.trace.instrumentation.karate2;

import static datadog.json.JsonMapper.toJson;

import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.Strings;
import io.karatelabs.core.FeatureRuntime;
import io.karatelabs.core.Globals;
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Tag;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public abstract class KarateUtils {

  private KarateUtils() {}

  public static final List<LibraryCapability> CAPABILITIES = Collections.emptyList();

  private static final ConcurrentHashMap<URI, String> CLASSPATH_NAME_CACHE =
      new ConcurrentHashMap<>();

  public static Feature getFeature(FeatureRuntime featureRuntime) {
    return featureRuntime.getFeature();
  }

  /**
   * Produces the karate-1.0-compatible suite identifier {@code "[<classpath-relative-path>]
   * <name>"} (e.g. {@code "[org/example/test_succeed] test succeed"}).
   *
   * <p>We can't use v2's {@code Feature.getNameForReport()} directly: it derives the path from
   * {@code Resource.getRelativePath()}, which v2 relativizes against the JVM working directory and
   * falls back to the absolute path when the file is outside it.
   *
   * <p>We recover that form by asking the classloader to resolve progressively longer suffixes of
   * the feature's path; the shortest suffix that resolves back to the same file is the
   * classpath-relative name. Falls back to Karate's working-directory-relative path if nothing
   * resolves.
   */
  public static String getFeatureNameForReport(Feature feature) {
    if (feature == null) {
      return null;
    }
    String classpathPath = resolveClasspathRelativeName(feature);
    String name = feature.getName();
    if (name == null || name.isEmpty()) {
      return "[" + classpathPath + "]";
    }
    return "[" + classpathPath + "] " + name;
  }

  private static String resolveClasspathRelativeName(Feature feature) {
    URI uri = feature.getResource() != null ? feature.getResource().getUri() : null;
    // use Karate's own relative path as fallback for anything the classloader can't resolve
    String fallback = resourceRelativeName(feature);
    if (uri == null) {
      return fallback;
    }
    return CLASSPATH_NAME_CACHE.computeIfAbsent(
        uri, u -> computeClasspathRelativeName(u, fallback));
  }

  private static String resourceRelativeName(Feature feature) {
    String relativePath =
        feature.getResource() != null ? feature.getResource().getRelativePath() : null;
    if (relativePath != null && !relativePath.isEmpty()) {
      return stripFeatureExtension(relativePath);
    }
    return feature.getName() != null ? feature.getName() : "";
  }

  private static String computeClasspathRelativeName(URI uri, String fallback) {
    Path path;
    try {
      path = Paths.get(uri);
    } catch (Exception e) {
      // non-file URI (e.g. a feature packaged inside a jar): can't walk filesystem segments
      return fallback;
    }
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = Feature.class.getClassLoader();
    }
    int segments = path.getNameCount();
    for (int start = segments - 1; start >= 0; start--) {
      StringBuilder candidate = new StringBuilder();
      for (int i = start; i < segments; i++) {
        if (i > start) {
          candidate.append('/');
        }
        candidate.append(path.getName(i).toString());
      }
      URL resolved = cl.getResource(candidate.toString());
      if (resolved != null) {
        try {
          if (Paths.get(resolved.toURI()).equals(path)) {
            return stripFeatureExtension(candidate.toString());
          }
        } catch (Exception ignored) {
          // continue searching
        }
      }
    }
    return fallback;
  }

  private static String stripFeatureExtension(String path) {
    return path.endsWith(".feature")
        ? path.substring(0, path.length() - ".feature".length())
        : path;
  }

  public static String getScenarioName(Scenario scenario) {
    String scenarioName = scenario.getName();
    if (Strings.isNotBlank(scenarioName)) {
      return scenarioName;
    } else {
      return scenario.getRefId();
    }
  }

  public static List<String> getCategories(List<Tag> tags) {
    if (tags == null) {
      return Collections.emptyList();
    }

    List<String> categories = new ArrayList<>(tags.size());
    for (Tag tag : tags) {
      categories.add(tag.getName());
    }
    return categories;
  }

  public static String getParameters(Scenario scenario) {
    return scenario.getExampleData() != null ? toJson(scenario.getExampleData()) : null;
  }

  public static TestIdentifier toTestIdentifier(Scenario scenario) {
    Feature feature = scenario.getFeature();
    String featureName = getFeatureNameForReport(feature);
    String scenarioName = getScenarioName(scenario);
    String parameters = getParameters(scenario);
    return new TestIdentifier(featureName, scenarioName, parameters);
  }

  public static TestDescriptor toTestDescriptor(ScenarioRuntime scenarioRuntime) {
    Scenario scenario = scenarioRuntime.getScenario();
    Feature feature = scenario.getFeature();
    String featureName = getFeatureNameForReport(feature);
    String scenarioName = getScenarioName(scenario);
    String parameters = getParameters(scenario);
    return new TestDescriptor(featureName, null, scenarioName, parameters, scenarioRuntime);
  }

  public static TestSuiteDescriptor toSuiteDescriptor(FeatureRuntime featureRuntime) {
    String featureName = getFeatureNameForReport(featureRuntime.getFeature());
    return new TestSuiteDescriptor(featureName, null);
  }

  public static String getKarateVersion() {
    return Globals.KARATE_VERSION;
  }

  public static List<LibraryCapability> capabilities() {
    return CAPABILITIES;
  }
}
