package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * !!!!!!!!!!!!!!!! IMPORTANT !!!!!!!!!!!!!!!! Do not use or refer to any classes from {@code
 * org.junit.platform.launcher} package in here: in some Gradle projects this package is not
 * available in CL where this instrumentation is injected.
 *
 * <p>Should you have to do something with those classes, do it in a dedicated utility class
 */
public abstract class JUnitPlatformUtils {

  private JUnitPlatformUtils() {}

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  /*
   * We have to support older versions of JUnit 5 that do not have certain methods that we would
   * like to use. We try to get method handles in runtime, and if we fail to do it there's a
   * fallback to alternative (less efficient) ways of getting the required info
   */
  private static final MethodHandle GET_JAVA_CLASS =
      METHOD_HANDLES.method(MethodSource.class, "getJavaClass");
  private static final MethodHandle GET_JAVA_METHOD =
      METHOD_HANDLES.method(MethodSource.class, "getJavaMethod");

  public static Class<?> getTestClass(MethodSource methodSource) {
    Class<?> javaClass = METHOD_HANDLES.invoke(GET_JAVA_CLASS, methodSource);
    if (javaClass != null) {
      return javaClass;
    }
    return ReflectionUtils.loadClass(methodSource.getClassName()).orElse(null);
  }

  public static Method getTestMethod(MethodSource methodSource) {
    Method javaMethod = METHOD_HANDLES.invoke(GET_JAVA_METHOD, methodSource);
    if (javaMethod != null) {
      return javaMethod;
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

  public static TestIdentifier toTestIdentifier(
      TestDescriptor testDescriptor, boolean includeParameters) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      String testSuiteName = methodSource.getClassName();
      String testName = methodSource.getMethodName();

      String testParameters;
      if (includeParameters) {
        String displayName = testDescriptor.getDisplayName();
        testParameters = getParameters(methodSource, displayName);
      } else {
        testParameters = null;
      }

      return new TestIdentifier(testSuiteName, testName, testParameters, null);

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

  public static Class<?> getJavaClass(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
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

  public static boolean isSuite(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    UniqueId.Segment lastSegment = segments.get(segments.size() - 1);
    return "class".equals(lastSegment.getType()) // "regular" JUnit test class
        || "nested-class".equals(lastSegment.getType()); // nested JUnit test class
  }
}
