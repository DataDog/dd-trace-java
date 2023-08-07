package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;

/**
 * A dedicated utility class for any logic that has to work with {@code org.junit.platform.launcher}
 * package or its children.
 */
public abstract class JUnitPlatformLauncherUtils {

  private static final MethodHandle GET_UNIQUE_ID_OBJECT;

  static {
    /*
     * We have to support older versions of JUnit 5 that do not have certain methods that we would
     * like to use. We try to get method handles in runtime, and if we fail to do it there's a
     * fallback to alternative (less efficient) ways of getting the required info
     */
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    GET_UNIQUE_ID_OBJECT = accessGetUniqueIdObject(lookup);
  }

  private static MethodHandle accessGetUniqueIdObject(MethodHandles.Lookup lookup) {
    try {
      MethodType returnsUniqueId = MethodType.methodType(UniqueId.class);
      return lookup.findVirtual(TestIdentifier.class, "getUniqueIdObject", returnsUniqueId);
    } catch (Exception e) {
      // assuming we're dealing with an older framework version
      // that does not have the methods we need;
      // fallback logic will be used in corresponding utility methods
      return null;
    }
  }

  private JUnitPlatformLauncherUtils() {}

  public static Class<?> getJavaClass(TestIdentifier testIdentifier) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (testSource instanceof ClassSource) {
      ClassSource classSource = (ClassSource) testSource;
      return classSource.getJavaClass();

    } else if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      return JUnitPlatformUtils.getTestClass(methodSource);

    } else {
      return null;
    }
  }

  public static boolean isSuite(TestIdentifier testIdentifier) {
    UniqueId uniqueId = getUniqueId(testIdentifier);
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    UniqueId.Segment lastSegment = segments.get(segments.size() - 1);
    return "class".equals(lastSegment.getType()) // "regular" JUnit test class
        || "nested-class".equals(lastSegment.getType()) // nested JUnit test class
        || "spec".equals(lastSegment.getType()) // Spock specification
        || "feature".equals(lastSegment.getType()); // Cucumber feature
  }

  public static @Nullable String getTestEngineId(final TestIdentifier testIdentifier) {
    UniqueId uniqueId = getUniqueId(testIdentifier);
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    ListIterator<UniqueId.Segment> iterator = segments.listIterator(segments.size());
    // Iterating from the end of the list,
    // since we want the last segment with type "engine".
    // In case junit-platform-suite engine is used,
    // its segment will be the first,
    // and the actual engine that runs the test
    // will be in some later segment
    while (iterator.hasPrevious()) {
      UniqueId.Segment segment = iterator.previous();
      if ("engine".equals(segment.getType())) {
        return segment.getValue();
      }
    }
    return null;
  }

  public static UniqueId getUniqueId(TestIdentifier testIdentifier) {
    if (GET_UNIQUE_ID_OBJECT != null) {
      try {
        return (UniqueId) GET_UNIQUE_ID_OBJECT.invokeExact(testIdentifier);
      } catch (Throwable e) {
        // fallback to slower mechanism below
      }
    }
    return UniqueId.parse(testIdentifier.getUniqueId());
  }

  public static Collection<TestEngine> getTestEngines(LauncherConfig config) {
    Set<TestEngine> engines = new LinkedHashSet<>();
    if (config.isTestEngineAutoRegistrationEnabled()) {
      ClassLoader defaultClassLoader = ClassLoaderUtils.getDefaultClassLoader();
      ServiceLoader.load(TestEngine.class, defaultClassLoader).forEach(engines::add);
    }
    engines.addAll(config.getAdditionalTestEngines());
    return engines;
  }

  public static final class Cucumber {
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

          UniqueId currentId = getUniqueId(current);
          if (JUnitPlatformUtils.Cucumber.isFeature(currentId)) {
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
  }
}
