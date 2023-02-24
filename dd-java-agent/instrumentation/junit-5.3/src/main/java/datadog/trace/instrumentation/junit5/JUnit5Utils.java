package datadog.trace.instrumentation.junit5;

import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

public abstract class JUnit5Utils {

  private static final Method GET_JAVA_CLASS;
  private static final Method GET_JAVA_METHOD;

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

  public static Class<?> getJavaClass(TestIdentifier testIdentifier) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (testSource instanceof ClassSource) {
      ClassSource classSource = (ClassSource) testSource;
      return classSource.getJavaClass();

    } else if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      return getTestClass(methodSource);

    } else {
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

  public static String getParameters(MethodSource methodSource, TestIdentifier testIdentifier) {
    if (methodSource.getMethodParameterTypes() == null
        || methodSource.getMethodParameterTypes().isEmpty()) {
      return null;
    }
    return "{\"metadata\":{\"test_name\":\""
        + Strings.escapeToJson(testIdentifier.getDisplayName())
        + "\"}}";
  }

  /*
   * JUnit5 considers parameterized or factory test cases as containers.
   * We need to differentiate this type of containers from "regular" ones, that are test classes
   */
  public static boolean isTestCase(TestIdentifier testIdentifier) {
    return testIdentifier.isContainer() && getMethodSourceOrNull(testIdentifier) != null;
  }

  public static boolean isRootContainer(TestIdentifier testIdentifier) {
    return !testIdentifier.getParentId().isPresent();
  }

  private static MethodSource getMethodSourceOrNull(TestIdentifier testIdentifier) {
    return (MethodSource)
        testIdentifier.getSource().filter(s -> s instanceof MethodSource).orElse(null);
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
}
