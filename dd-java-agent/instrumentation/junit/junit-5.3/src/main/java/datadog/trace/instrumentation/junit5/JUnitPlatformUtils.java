package datadog.trace.instrumentation.junit5;

import static datadog.json.JsonMapper.toJson;

import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.util.ComparableVersion;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * !!!!!!!!!!!!!!!! IMPORTANT !!!!!!!!!!!!!!!! Do not use or refer to any classes from {@code
 * org.junit.platform.launcher} package in here: in some Gradle projects this package is not
 * available in CL where this instrumentation is injected.
 *
 * <p>Should you have to do something with those classes, do it in a dedicated utility class
 */
public abstract class JUnitPlatformUtils {

  public static final String RETRY_DESCRIPTOR_ID_SUFFIX = "retry-attempt";

  private static final Logger LOGGER = LoggerFactory.getLogger(JUnitPlatformUtils.class);

  public static final String ENGINE_ID_CUCUMBER = "cucumber";
  public static final String ENGINE_ID_SPOCK = "spock";

  public static final ComparableVersion junitV58 = new ComparableVersion("5.8");

  public static final List<LibraryCapability> JUNIT_CAPABILITIES_BASE =
      Arrays.asList(
          LibraryCapability.TIA,
          LibraryCapability.ATR,
          LibraryCapability.EFD,
          LibraryCapability.IMPACTED,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.DISABLED,
          LibraryCapability.ATTEMPT_TO_FIX);

  public static final List<LibraryCapability> JUNIT_CAPABILITIES_ORDERING =
      Arrays.asList(
          LibraryCapability.TIA,
          LibraryCapability.ATR,
          LibraryCapability.EFD,
          LibraryCapability.IMPACTED,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.DISABLED,
          LibraryCapability.ATTEMPT_TO_FIX,
          LibraryCapability.FAIL_FAST);

  public static final List<LibraryCapability> SPOCK_CAPABILITIES =
      Arrays.asList(
          LibraryCapability.TIA,
          LibraryCapability.ATR,
          LibraryCapability.EFD,
          LibraryCapability.IMPACTED,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.DISABLED,
          LibraryCapability.ATTEMPT_TO_FIX);

  public static final List<LibraryCapability> CUCUMBER_CAPABILITIES =
      Arrays.asList(
          LibraryCapability.TIA,
          LibraryCapability.ATR,
          LibraryCapability.EFD,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.DISABLED,
          LibraryCapability.ATTEMPT_TO_FIX);

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

  private static Class<?> getTestClass(MethodSource methodSource) {
    Class<?> javaClass = METHOD_HANDLES.invoke(GET_JAVA_CLASS, methodSource);
    if (javaClass != null) {
      return javaClass;
    }
    try {
      return ClassLoaderUtils.getDefaultClassLoader().loadClass(methodSource.getClassName());
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private static Method getTestMethod(MethodSource methodSource) {
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
      LOGGER.debug("Could not find method {} in class {}", methodName, testClass, e);
      LOGGER.warn("Could not find test method");
      return null;
    }
  }

  public static String getParameters(MethodSource methodSource, String displayName) {
    if (methodSource.getMethodParameterTypes() == null
        || methodSource.getMethodParameterTypes().isEmpty()) {
      return null;
    }
    return "{\"metadata\":{\"test_name\":" + toJson(displayName) + "}}";
  }

  @Nullable
  public static TestIdentifier toTestIdentifier(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      String testSuiteName = methodSource.getClassName();
      String testName = methodSource.getMethodName();
      String displayName = testDescriptor.getDisplayName();
      String testParameters = getParameters(methodSource, displayName);
      return new TestIdentifier(testSuiteName, testName, testParameters);

    } else {
      return null;
    }
  }

  @Nonnull
  public static TestSourceData toTestSourceData(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return TestSourceData.UNKNOWN;
    }

    MethodSource methodSource = (MethodSource) testSource;
    TestDescriptor suiteDescriptor = getSuiteDescriptor(testDescriptor);
    Class<?> testClass =
        suiteDescriptor != null ? getJavaClass(suiteDescriptor) : getTestClass(methodSource);
    Method testMethod = getTestMethod(methodSource);
    String testMethodName = methodSource.getMethodName();
    return new TestSourceData(testClass, testMethod, testMethodName);
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
    AgentSpan span = AgentTracer.activeSpan();
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

  public static boolean isParameterizedTest(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    UniqueId.Segment lastSegment = segments.get(segments.size() - 1);
    return "test-template".equals(lastSegment.getType());
  }

  public static boolean isRetry(TestDescriptor testDescriptor) {
    return getIDSegmentValue(testDescriptor, RETRY_DESCRIPTOR_ID_SUFFIX) != null;
  }

  private static String getIDSegmentValue(TestDescriptor testDescriptor, String segmentName) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    for (UniqueId.Segment segment : segments) {
      if (segmentName.equals(segment.getType())) {
        return segment.getValue();
      }
    }
    return null;
  }

  public static TestDescriptor getSuiteDescriptor(TestDescriptor testDescriptor) {
    while (testDescriptor != null && !isSuite(testDescriptor)) {
      testDescriptor = testDescriptor.getParent().orElse(null);
    }
    return testDescriptor;
  }

  public static TestFrameworkInstrumentation engineToFramework(TestEngine testEngine) {
    String testEngineClassName = testEngine.getClass().getName();
    if (testEngineClassName.startsWith("io.cucumber")) {
      return TestFrameworkInstrumentation.CUCUMBER;
    } else if (testEngineClassName.startsWith("org.spockframework")) {
      return TestFrameworkInstrumentation.SPOCK;
    } else {
      return TestFrameworkInstrumentation.JUNIT5;
    }
  }

  public static String getEngineId(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    ListIterator<UniqueId.Segment> it = segments.listIterator(segments.size());
    while (it.hasPrevious()) {
      UniqueId.Segment segment = it.previous();
      if ("engine".equals(segment.getType())) {
        return segment.getValue();
      }
    }
    return null;
  }

  public static TestFrameworkInstrumentation engineIdToFramework(String engineId) {
    if (ENGINE_ID_CUCUMBER.equals(engineId)) {
      return TestFrameworkInstrumentation.CUCUMBER;
    } else if (ENGINE_ID_SPOCK.equals(engineId)) {
      return TestFrameworkInstrumentation.SPOCK;
    } else {
      return TestFrameworkInstrumentation.JUNIT5;
    }
  }

  // only used in junit5 and spock, cucumber has its own utils method
  @Nullable
  public static String getFrameworkVersion(TestEngine testEngine) {
    return testEngine.getVersion().orElse(null);
  }

  public static boolean isJunitTestOrderingSupported(String version) {
    return version != null && junitV58.compareTo(new ComparableVersion(version)) <= 0;
  }

  public static List<LibraryCapability> capabilities(TestEngine testEngine) {
    TestFrameworkInstrumentation framework = engineToFramework(testEngine);
    if (framework.equals(TestFrameworkInstrumentation.CUCUMBER)) {
      return CUCUMBER_CAPABILITIES;
    } else if (framework.equals(TestFrameworkInstrumentation.SPOCK)) {
      return SPOCK_CAPABILITIES;
    } else {
      if (isJunitTestOrderingSupported(getFrameworkVersion(testEngine))) {
        return JUNIT_CAPABILITIES_ORDERING;
      } else {
        return JUNIT_CAPABILITIES_BASE;
      }
    }
  }

  public static List<String> getTags(TestDescriptor testDescriptor) {
    return testDescriptor.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
  }
}
