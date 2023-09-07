package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.util.Strings;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import javax.annotation.Nullable;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * !!!!!!!!!!!!!!!! IMPORTANT !!!!!!!!!!!!!!!! Do not use or refer to {@code
 * datadog.trace.instrumentation.junit5.JunitPlatformLauncherUtils} or any classes from {@code
 * org.junit.platform.launcher} package in here: in some Gradle projects this package is not
 * available in CL where this instrumentation is injected.
 *
 * <p>Should you have to do something with those classes, do it in {@code
 * datadog.trace.instrumentation.junit5.JunitPlatformLauncherUtils}
 */
public abstract class JUnitPlatformUtils {

  private JUnitPlatformUtils() {}

  private static final MethodHandle GET_JAVA_CLASS;
  private static final MethodHandle GET_JAVA_METHOD;

  static {
    /*
     * We have to support older versions of JUnit 5 that do not have certain methods that we would
     * like to use. We try to get method handles in runtime, and if we fail to do it there's a
     * fallback to alternative (less efficient) ways of getting the required info
     */
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    GET_JAVA_CLASS = accessGetJavaClass(lookup);
    GET_JAVA_METHOD = accessGetJavaMethod(lookup);
  }

  private static MethodHandle accessGetJavaClass(MethodHandles.Lookup lookup) {
    try {
      MethodType returnsClass = MethodType.methodType(Class.class);
      return lookup.findVirtual(MethodSource.class, "getJavaClass", returnsClass);
    } catch (Exception e) {
      // assuming we're dealing with an older framework version
      // that does not have the methods we need;
      // fallback logic will be used in corresponding utility methods
      return null;
    }
  }

  private static MethodHandle accessGetJavaMethod(MethodHandles.Lookup lookup) {
    try {
      MethodType returnsMethod = MethodType.methodType(Method.class);
      return lookup.findVirtual(MethodSource.class, "getJavaMethod", returnsMethod);
    } catch (Exception e) {
      // assuming we're dealing with an older framework version
      // that does not have the methods we need;
      // fallback logic will be used in corresponding utility methods
      return null;
    }
  }

  public static Class<?> getTestClass(MethodSource methodSource) {
    if (GET_JAVA_CLASS != null) {
      try {
        return (Class<?>) GET_JAVA_CLASS.invokeExact(methodSource);
      } catch (Throwable e) {
        // ignore, fallback to slower mechanism below
      }
    }
    return ReflectionUtils.loadClass(methodSource.getClassName()).orElse(null);
  }

  public static Method getTestMethod(MethodSource methodSource) {
    if (GET_JAVA_METHOD != null) {
      try {
        return (Method) GET_JAVA_METHOD.invokeExact(methodSource);
      } catch (Throwable e) {
        // ignore, fallback to slower mechanism below
      }
    }

    Class<?> testClass = getTestClass(methodSource);
    if (testClass == null) {
      return null;
    }

    String methodName = methodSource.getMethodName();
    if (methodName == null || methodName.isEmpty()) {
      return null;
    }

    try {
      return ReflectionUtils.findMethod(
              testClass, methodName, methodSource.getMethodParameterTypes())
          .orElse(null);
    } catch (JUnitException e) {
      return null;
    }
  }

  public static String getParameters(MethodSource methodSource, String displayName) {
    if (methodSource.getMethodParameterTypes() == null
        || methodSource.getMethodParameterTypes().isEmpty()) {
      return null;
    }
    return "{\"metadata\":{\"test_name\":\"" + Strings.escapeToJson(displayName) + "\"}}";
  }

  public static String getTestName(
      String displayName, MethodSource methodSource, String testEngineId) {
    return Spock.ENGINE_ID.equals(testEngineId) ? displayName : methodSource.getMethodName();
  }

  @Nullable
  public static Method getTestMethod(MethodSource methodSource, String testEngineId) {
    return Spock.ENGINE_ID.equals(testEngineId)
        ? Spock.getSpockTestMethod(methodSource)
        : getTestMethod(methodSource);
  }

  public static SkippableTest toSkippableTest(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      String testSuiteName = methodSource.getClassName();
      String displayName = testDescriptor.getDisplayName();
      UniqueId uniqueId = testDescriptor.getUniqueId();
      String testEngineId = uniqueId.getEngineId().orElse(null);
      String testName = getTestName(displayName, methodSource, testEngineId);

      String testParameters = getParameters(methodSource, displayName);

      return new SkippableTest(testSuiteName, testName, testParameters, null);

    } else if (testSource instanceof ClasspathResourceSource) {
      ClasspathResourceSource classpathResourceSource = (ClasspathResourceSource) testSource;
      String classpathResourceName = classpathResourceSource.getClasspathResourceName();

      Pair<String, String> names =
          Cucumber.getFeatureAndScenarioNames(testDescriptor, classpathResourceName);
      String testSuiteName = names.getLeft();
      String testName = names.getRight();
      return new SkippableTest(testSuiteName, testName, null, null);

    } else {
      return null;
    }
  }

  public static boolean isAssumptionFailure(Throwable throwable) {
    switch (throwable.getClass().getName()) {
      case "org.junit.AssumptionViolatedException":
      case "org.junit.internal.AssumptionViolatedException":
      case "org.opentest4j.TestAbortedException":
      case "org.opentest4j.TestSkippedException":
        // If the test assumption fails, one of the following exceptions will be thrown.
        // The consensus is to treat "assumptions failure" as skipped tests.
        return true;
      default:
        return false;
    }
  }

  public static boolean isTestInProgress() {
    AgentScope activeScope = AgentTracer.activeScope();
    if (activeScope == null) {
      return false;
    }
    AgentSpan span = activeScope.span();
    if (span == null) {
      return false;
    }
    return InternalSpanTypes.TEST.toString().equals(span.getSpanType());
  }

  public static final class Spock {
    public static final String ENGINE_ID = "spock";

    private static final Class<Annotation> SPOCK_FEATURE_METADATA;

    private static final MethodHandle SPOCK_FEATURE_NAME;

    static {
      /*
       * Spock's classes are accessed via reflection and method handles
       * since they are loaded by a different classloader in some envs
       */
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      ClassLoader defaultClassLoader = ClassLoaderUtils.getDefaultClassLoader();
      SPOCK_FEATURE_METADATA = accessSpockFeatureMetadata(defaultClassLoader);
      SPOCK_FEATURE_NAME = accessSpockFeatureName(lookup, SPOCK_FEATURE_METADATA);
    }

    private static Class<Annotation> accessSpockFeatureMetadata(ClassLoader classLoader) {
      try {
        return (Class<Annotation>)
            classLoader.loadClass("org.spockframework.runtime.model.FeatureMetadata");
      } catch (Exception e) {
        return null;
      }
    }

    private static MethodHandle accessSpockFeatureName(
        MethodHandles.Lookup lookup, Class<Annotation> spockFeatureMetadata) {
      if (spockFeatureMetadata == null) {
        return null;
      }
      try {
        MethodType returnsString = MethodType.methodType(String.class);
        return lookup.findVirtual(spockFeatureMetadata, "name", returnsString);
      } catch (Exception e) {
        return null;
      }
    }

    public static Method getSpockTestMethod(MethodSource methodSource) {
      String methodName = methodSource.getMethodName();
      if (methodName == null) {
        return null;
      }

      Class<?> testClass = getTestClass(methodSource);
      if (testClass == null) {
        return null;
      }

      if (SPOCK_FEATURE_METADATA == null || SPOCK_FEATURE_NAME == null) {
        return null;
      }

      try {
        for (Method declaredMethod : testClass.getDeclaredMethods()) {
          Annotation featureMetadata = declaredMethod.getAnnotation(SPOCK_FEATURE_METADATA);
          if (featureMetadata == null) {
            continue;
          }

          String annotatedName = (String) SPOCK_FEATURE_NAME.invoke(featureMetadata);
          if (methodName.equals(annotatedName)) {
            return declaredMethod;
          }
        }

      } catch (Throwable e) {
        // ignore
      }

      return null;
    }
  }

  public static final class Cucumber {
    public static final String ENGINE_ID = "cucumber";

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

    public static boolean isFeature(UniqueId uniqueId) {
      List<UniqueId.Segment> segments = uniqueId.getSegments();
      UniqueId.Segment lastSegment = segments.listIterator(segments.size()).previous();
      return "feature".equals(lastSegment.getType());
    }
  }
}
