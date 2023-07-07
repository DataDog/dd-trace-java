package datadog.trace.instrumentation.junit5;

import datadog.trace.util.Strings;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.support.descriptor.MethodSource;

public abstract class TestFrameworkUtils {
  private static final String SPOCK_ENGINE_ID = "spock";

  static final Method GET_JAVA_CLASS;
  static final Method GET_JAVA_METHOD;

  static {
    GET_JAVA_CLASS = accessGetJavaClass();
    GET_JAVA_METHOD = accessGetJavaMethod();
  }

  private static Method accessGetJavaClass() {
    try {
      // the method was added in JUnit 5.7
      // if older version of the framework is used, we fall back to a slower mechanism
      Method method = MethodSource.class.getMethod("getJavaClass");
      method.setAccessible(true);
      return method;
    } catch (Exception e) {
      return null;
    }
  }

  private static Method accessGetJavaMethod() {
    try {
      // the method was added in JUnit 5.7
      // if older version of the framework is used, we fall back to a slower mechanism
      Method method = MethodSource.class.getMethod("getJavaMethod");
      method.setAccessible(true);
      return method;
    } catch (Exception e) {
      return null;
    }
  }

  public static Class<?> getTestClass(MethodSource methodSource) {
    if (GET_JAVA_CLASS != null && GET_JAVA_CLASS.isAccessible()) {
      try {
        return (Class<?>) GET_JAVA_CLASS.invoke(methodSource);
      } catch (Exception e) {
        // ignore, fallback to slower mechanism below
      }
    }
    return ReflectionUtils.loadClass(methodSource.getClassName()).orElse(null);
  }

  public static Method getTestMethod(MethodSource methodSource) {
    if (GET_JAVA_METHOD != null && GET_JAVA_METHOD.isAccessible()) {
      try {
        return (Method) GET_JAVA_METHOD.invoke(methodSource);
      } catch (Exception e) {
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

    try {
      // annotation class has to be loaded like this since at runtime
      // it is absent from the classloader that loads instrumentation code
      Class<Annotation> featureMetadataClass =
          (Class<Annotation>)
              testClass
                  .getClassLoader()
                  .loadClass("org.spockframework.runtime.model.FeatureMetadata");
      Method nameMethod = featureMetadataClass.getDeclaredMethod("name");

      for (Method declaredMethod : testClass.getDeclaredMethods()) {
        Annotation featureMetadata = declaredMethod.getAnnotation(featureMetadataClass);
        if (featureMetadata == null) {
          continue;
        }

        String annotatedName = (String) nameMethod.invoke(featureMetadata);
        if (methodName.equals(annotatedName)) {
          return declaredMethod;
        }
      }

    } catch (Exception e) {
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
}
