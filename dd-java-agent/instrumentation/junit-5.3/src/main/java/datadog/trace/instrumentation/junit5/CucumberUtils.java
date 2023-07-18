package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import javax.annotation.Nullable;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class CucumberUtils {

  public static final String CUCUMBER_ENGINE_ID = "cucumber";

  public static @Nullable String getCucumberVersion(TestEngine cucumberEngine) {
    try (InputStream cucumberPropsStream =
        cucumberEngine
            .getClass()
            .getClassLoader()
            .getResourceAsStream(
                "META-INF/maven/io.cucumber/cucumber-junit-platform-engine/pom.properties")) {
      Properties cucumberProps = new Properties();
      cucumberProps.load(cucumberPropsStream);
      String version = cucumberProps.getProperty("version");
      if (version != null) {
        return version;
      }
    } catch (Exception e) {
      // fallback below
    }
    // might return "DEVELOPMENT" even for releases
    return cucumberEngine.getVersion().orElse(null);
  }

  public static Pair<String, String> getFeatureAndScenarioNames(
      TestDescriptor testDescriptor, String fallbackFeatureName) {
    String featureName = fallbackFeatureName;

    Deque<TestDescriptor> scenarioDescriptors = new ArrayDeque<>();
    scenarioDescriptors.push(testDescriptor);

    TestDescriptor current = testDescriptor;
    while (true) {
      Optional<TestDescriptor> parent = current.getParent();
      if (!parent.isPresent()) {
        break;
      }

      current = parent.get();
      UniqueId currentId = current.getUniqueId();
      if (isFeature(currentId)) {
        featureName = current.getDisplayName();
        break;

      } else {
        scenarioDescriptors.push(current);
      }
    }

    StringBuilder scenarioName = new StringBuilder();
    while (!scenarioDescriptors.isEmpty()) {
      TestDescriptor descriptor = scenarioDescriptors.pop();
      scenarioName.append(descriptor.getDisplayName());
      if (!scenarioDescriptors.isEmpty()) {
        scenarioName.append('.');
      }
    }

    return Pair.of(featureName, scenarioName.toString());
  }

  public static Pair<String, String> getFeatureAndScenarioNames(
      TestPlan testPlan, TestIdentifier scenario, String fallbackFeatureName) {
    String featureName = fallbackFeatureName;

    Deque<TestIdentifier> scenarioIdentifiers = new ArrayDeque<>();
    scenarioIdentifiers.push(scenario);

    try {
      TestIdentifier current = scenario;
      while (true) {
        current = testPlan.getParent(current).orElse(null);
        if (current == null) {
          break;
        }

        UniqueId currentId = TestFrameworkUtils.getUniqueId(current);
        if (isFeature(currentId)) {
          featureName = current.getDisplayName();
          break;

        } else {
          scenarioIdentifiers.push(current);
        }
      }

    } catch (Exception e) {
      // ignore
    }

    StringBuilder scenarioName = new StringBuilder();
    while (!scenarioIdentifiers.isEmpty()) {
      TestIdentifier identifier = scenarioIdentifiers.pop();
      scenarioName.append(identifier.getDisplayName());
      if (!scenarioIdentifiers.isEmpty()) {
        scenarioName.append('.');
      }
    }

    return Pair.of(featureName, scenarioName.toString());
  }

  private static boolean isFeature(UniqueId uniqueId) {
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    UniqueId.Segment lastSegment = segments.listIterator(segments.size()).previous();
    return "feature".equals(lastSegment.getType());
  }
}
