package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.util.Strings;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ListIterator;
import javax.annotation.Nullable;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

public abstract class TestFrameworkUtils {
  private static final String SPOCK_ENGINE_ID = "spock";

  private static final MethodHandle GET_JAVA_CLASS;
  private static final MethodHandle GET_JAVA_METHOD;
  private static final MethodHandle GET_UNIQUE_ID_OBJECT;

  private static final Class<Annotation> SPOCK_FEATURE_METADATA;

  private static final MethodHandle SPOCK_FEATURE_NAME;

  static {
    /*
     * We have to support older versions of JUnit 5 that do not have certain methods that we would
     * like to use. We try to get method handles in runtime, and if we fail to do it there's a
     * fallback to alternative (less efficient) ways of getting the required info
     */
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    GET_JAVA_CLASS = accessGetJavaClass(lookup);
    GET_JAVA_METHOD = accessGetJavaMethod(lookup);
    GET_UNIQUE_ID_OBJECT = accessGetUniqueIdObject(lookup);

    /*
     * Spock's classes are accessed via reflection and method handles
     * since they are loaded by a different classloader in some envs
     */
    ClassLoader defaultClassLoader = ClassLoaderUtils.getDefaultClassLoader();
    SPOCK_FEATURE_METADATA = accessSpockFeatureMetadata(defaultClassLoader);
    SPOCK_FEATURE_NAME = accessSpockFeatureName(lookup, SPOCK_FEATURE_METADATA);
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

  public static String getParameters(MethodSource methodSource, String displayName) {
    if (methodSource.getMethodParameterTypes() == null
        || methodSource.getMethodParameterTypes().isEmpty()) {
      return null;
    }
    return "{\"metadata\":{\"test_name\":\"" + Strings.escapeToJson(displayName) + "\"}}";
  }

  public static String getTestName(
      String displayName, MethodSource methodSource, String testEngineId) {
    return SPOCK_ENGINE_ID.equals(testEngineId) ? displayName : methodSource.getMethodName();
  }

  @Nullable
  public static Method getTestMethod(MethodSource methodSource, String testEngineId) {
    return SPOCK_ENGINE_ID.equals(testEngineId)
        ? getSpockTestMethod(methodSource)
        : getTestMethod(methodSource);
  }

  public static SkippableTest toSkippableTest(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return null;
    }

    MethodSource methodSource = (MethodSource) testSource;
    String testSuiteName = methodSource.getClassName();
    String displayName = testDescriptor.getDisplayName();
    UniqueId uniqueId = testDescriptor.getUniqueId();
    String testEngineId = uniqueId.getEngineId().orElse(null);
    String testName = getTestName(displayName, methodSource, testEngineId);

    String testParameters = getParameters(methodSource, displayName);

    return new SkippableTest(testSuiteName, testName, testParameters, null);
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

  private static UniqueId getUniqueId(TestIdentifier testIdentifier) {
    if (GET_UNIQUE_ID_OBJECT != null) {
      try {
        return (UniqueId) GET_UNIQUE_ID_OBJECT.invokeExact(testIdentifier);
      } catch (Throwable e) {
        // fallback to slower mechanism below
      }
    }
    return UniqueId.parse(testIdentifier.getUniqueId());
  }
}
