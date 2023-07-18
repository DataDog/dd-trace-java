package datadog.trace.instrumentation.junit5;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.support.descriptor.MethodSource;

public class SpockUtils {

  public static final String SPOCK_ENGINE_ID = "spock";

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

    Class<?> testClass = TestFrameworkUtils.getTestClass(methodSource);
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
