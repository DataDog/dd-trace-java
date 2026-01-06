package datadog.trace.instrumentation.testng;

import datadog.json.JsonWriter;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.ComparableVersion;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IClass;
import org.testng.IRetryAnalyzer;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.Test;
import org.testng.internal.ConstructorOrMethod;
import org.testng.internal.ITestResultNotifier;
import org.testng.xml.XmlTest;

public abstract class TestNGUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestNGUtils.class);

  private static final datadog.trace.util.MethodHandles METHOD_HANDLES =
      new datadog.trace.util.MethodHandles(TestNG.class.getClassLoader());

  private static final MethodHandle XML_TEST_GET_PARALLEL =
      METHOD_HANDLES.method(XmlTest.class, "getParallel");

  private static final MethodHandle TEST_RESULT_WAS_RETRIED =
      METHOD_HANDLES.method(ITestResult.class, "wasRetried");
  private static final MethodHandle TEST_METHOD_GET_RETRY_ANALYZER =
      METHOD_HANDLES.method(ITestNGMethod.class, "getRetryAnalyzer", ITestResult.class);
  private static final MethodHandle TEST_METHOD_GET_RETRY_ANALYZER_LEGACY =
      METHOD_HANDLES.method(ITestNGMethod.class, "getRetryAnalyzer");

  private static final ComparableVersion testNGv75 = new ComparableVersion("7.5");
  private static final ComparableVersion testNGv70 = new ComparableVersion("7.0");

  private static Class<?> getTestClass(final ITestResult result) {
    IClass testClass = result.getTestClass();
    if (testClass == null) {
      return null;
    }
    return testClass.getRealClass();
  }

  private static Method getTestMethod(final ITestResult result) {
    ITestNGMethod method = result.getMethod();
    if (method == null) {
      return null;
    }
    ConstructorOrMethod constructorOrMethod = method.getConstructorOrMethod();
    if (constructorOrMethod == null) {
      return null;
    }
    return constructorOrMethod.getMethod();
  }

  public static TestSourceData toTestSourceData(final ITestResult result) {
    Class<?> testClass = getTestClass(result);
    Method testMethod = getTestMethod(result);
    return new TestSourceData(testClass, testMethod);
  }

  public static String getParameters(final ITestResult result) {
    return getParameters(result.getParameters());
  }

  public static String getParameters(Object[] parameters) {
    if (parameters == null || parameters.length == 0) {
      return null;
    }

    // We build manually the JSON for test.parameters tag.
    // Example: {"arguments":{"0":"param1","1":"param2"}}
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject().name("arguments").beginObject();
      for (int i = 0; i < parameters.length; i++) {
        writer.name(Integer.toString(i)).value(String.valueOf(parameters[i]));
      }
      writer.endObject().endObject();
      return writer.toString();
    }
  }

  public static List<String> getGroups(ITestResult result) {
    ITestNGMethod method = result.getMethod();
    if (method == null) {
      return null;
    }
    String[] groups = method.getGroups();
    if (groups == null) {
      return null;
    }
    return Arrays.asList(groups);
  }

  public static List<String> getGroups(ITestClass testClass) {
    Class<?> realClass = testClass.getRealClass();
    List<String> groups = new ArrayList<>();
    while (realClass != null) {
      Test testAnnotation = realClass.getAnnotation(Test.class);
      if (testAnnotation != null) {
        Collections.addAll(groups, testAnnotation.groups());
      }

      realClass = realClass.getSuperclass();
    }
    return !groups.isEmpty() ? groups : null;
  }

  public static List<String> getGroups(Method method) {
    List<String> groups = new ArrayList<>();

    Test methodAnnotation = method.getAnnotation(Test.class);
    if (methodAnnotation != null) {
      Collections.addAll(groups, methodAnnotation.groups());
    }

    Class<?> clazz = method.getDeclaringClass();
    do {
      Test classAnnotation = clazz.getAnnotation(Test.class);
      if (classAnnotation != null) {
        Collections.addAll(groups, classAnnotation.groups());
      }
      clazz = clazz.getSuperclass();
    } while (clazz != null);

    return groups;
  }

  public static TestNGClassListener getTestNGClassListener(ITestContext testContext) {
    try {
      if (!(testContext instanceof ITestResultNotifier)) {
        throw new RuntimeException(
            "Test context is not an instance of ITestResultNotifier: " + testContext);
      }

      ITestResultNotifier testResultNotifier = (ITestResultNotifier) testContext;
      List<ITestListener> testListeners = testResultNotifier.getTestListeners();
      if (testListeners == null) {
        throw new RuntimeException("Test listeners are null");
      }

      for (ITestListener testListener : testListeners) {
        if (testListener instanceof TestNGClassListener) {
          return (TestNGClassListener) testListener;
        }
      }
      throw new RuntimeException("Tracing listener not found: " + testListeners);

    } catch (Exception e) {
      throw new RuntimeException("Could not get tracing listener", e);
    }
  }

  public static boolean isParallelized(ITestClass testClass) {
    try {
      // reflection needs to be used since XmlTest#getParallel
      // has different return type in different versions,
      // and if the method is invoked directly,
      // the instrumentation will not get past Muzzle checks
      Object parallel = METHOD_HANDLES.invoke(XML_TEST_GET_PARALLEL, testClass.getXmlTest());
      return parallel != null
          && ("methods".equals(parallel.toString()) || "tests".equals(parallel.toString()));
    } catch (Throwable e) {
      LOGGER.warn("Error while checking if a test class is paralellized");
      return false;
    }
  }

  public static String getTestNGVersion() {
    Class<TestNG> testNg = TestNG.class;
    Package pkg = testNg.getPackage();

    String implVersion = pkg.getImplementationVersion();
    if (implVersion != null) {
      return implVersion;
    }

    String specVersion = pkg.getSpecificationVersion();
    if (specVersion != null) {
      return specVersion;
    }

    try {
      String className = testNg.getSimpleName() + ".class";
      URL classResource = testNg.getResource(className);
      if (classResource == null) {
        return null;
      }

      String classPath = classResource.toString();
      String manifestPath =
          classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
      try (InputStream manifestStream = new java.net.URL(manifestPath).openStream()) {
        Properties manifestProperties = new Properties();
        manifestProperties.load(manifestStream);
        return manifestProperties.getProperty("Bundle-Version");
      }

    } catch (Exception e) {
      return null;
    }
  }

  public static boolean wasRetried(ITestResult result) {
    try {
      return METHOD_HANDLES.invoke(TEST_RESULT_WAS_RETRIED, result);
    } catch (Throwable e) {
      return false;
    }
  }

  public static IRetryAnalyzer getRetryAnalyzer(ITestResult result) {
    ITestNGMethod method = result.getMethod();
    if (method == null) {
      return null;
    }
    IRetryAnalyzer analyzer = METHOD_HANDLES.invoke(TEST_METHOD_GET_RETRY_ANALYZER, method, result);
    if (analyzer != null) {
      return analyzer;
    } else {
      return METHOD_HANDLES.invoke(TEST_METHOD_GET_RETRY_ANALYZER_LEGACY, method);
    }
  }

  @Nonnull
  public static TestIdentifier toTestIdentifier(
      Method method, Object instance, Object[] parameters) {
    Class<?> testClass = instance != null ? instance.getClass() : method.getDeclaringClass();
    String testSuiteName = testClass.getName();
    String testName = method.getName();
    String testParameters = TestNGUtils.getParameters(parameters);
    return new TestIdentifier(testSuiteName, testName, testParameters);
  }

  @Nonnull
  public static TestIdentifier toTestIdentifier(ITestResult result) {
    String testSuiteName = result.getInstanceName();
    String testName =
        (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);
    return new TestIdentifier(testSuiteName, testName, testParameters);
  }

  @Nonnull
  public static TestSuiteDescriptor toSuiteDescriptor(ITestClass testClass) {
    String testSuiteName = testClass.getName();
    Class<?> testSuiteClass = testClass.getRealClass();
    return new TestSuiteDescriptor(testSuiteName, testSuiteClass);
  }

  public static boolean isEFDSupported(String version) {
    return version != null && testNGv75.compareTo(new ComparableVersion(version)) <= 0;
  }

  public static boolean isExceptionSuppressionSupported(String version) {
    return version != null && testNGv75.compareTo(new ComparableVersion(version)) <= 0;
  }

  public static boolean isTestOrderingSupported(String version) {
    return version != null && testNGv70.compareTo(new ComparableVersion(version)) <= 0;
  }

  public static List<LibraryCapability> capabilities(String version) {
    List<LibraryCapability> baseCapabilities =
        new ArrayList<>(
            Arrays.asList(
                LibraryCapability.TIA, LibraryCapability.IMPACTED, LibraryCapability.DISABLED));

    boolean isEFDSupported = isEFDSupported(version);
    boolean isExceptionSuppressionSupported = isExceptionSuppressionSupported(version);
    if (isExceptionSuppressionSupported) {
      baseCapabilities.add(LibraryCapability.ATR);
      baseCapabilities.add(LibraryCapability.FTR);
      baseCapabilities.add(LibraryCapability.QUARANTINE);
    }
    if (isEFDSupported) {
      baseCapabilities.add(LibraryCapability.EFD);
    }
    if (isExceptionSuppressionSupported && isEFDSupported) {
      baseCapabilities.add(LibraryCapability.ATTEMPT_TO_FIX);
    }
    if (isTestOrderingSupported(version)) {
      baseCapabilities.add(LibraryCapability.FAIL_FAST);
    }

    return baseCapabilities;
  }
}
