package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import datadog.trace.api.civisibility.config.TestIdentifier;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.Properties;
import javax.annotation.Nullable;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;

public abstract class CucumberUtils {

  static {
    TestIdentifierFactory.register("cucumber", CucumberUtils::toTestIdentifier);
  }

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
        featureName = currentId.getLastSegment().getValue() + ":" + current.getDisplayName();
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

  public static String getFeatureName(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    ListIterator<UniqueId.Segment> it = segments.listIterator(segments.size());
    while (it.hasPrevious()) {
      UniqueId.Segment segment = it.previous();
      if ("feature".equals(segment.getType())) {
        return segment.getValue() + ":" + testDescriptor.getLegacyReportingName();
      }
    }
    return testDescriptor.getLegacyReportingName();
  }

  public static boolean isFeature(UniqueId uniqueId) {
    UniqueId.Segment lastSegment = uniqueId.getLastSegment();
    return "feature".equals(lastSegment.getType());
  }

  public static TestIdentifier toTestIdentifier(
      TestDescriptor testDescriptor, boolean includeParameters) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof ClasspathResourceSource) {
      ClasspathResourceSource classpathResourceSource = (ClasspathResourceSource) testSource;
      String classpathResourceName = classpathResourceSource.getClasspathResourceName();

      Pair<String, String> names =
          getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
      String testSuiteName = names.getLeft();
      String testName = names.getRight();
      return new TestIdentifier(testSuiteName, testName, null, null);

    } else {
      return null;
    }
  }
}
