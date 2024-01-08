package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.util.Strings;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.testng.IClass;
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

  private static final MethodHandle XML_TEST_GET_PARALLEL = accessGetParallel();

  private static MethodHandle accessGetParallel() {
    try {
      Method getParallel = XmlTest.class.getMethod("getParallel");
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      return lookup.unreflect(getParallel);

    } catch (Exception e) {
      return null;
    }
  }

  public static Class<?> getTestClass(final ITestResult result) {
    IClass testClass = result.getTestClass();
    if (testClass == null) {
      return null;
    }
    return testClass.getRealClass();
  }

  public static Method getTestMethod(final ITestResult result) {
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

  public static String getParameters(final ITestResult result) {
    return getParameters(result.getParameters());
  }

  public static String getParameters(Object[] parameters) {
    if (parameters == null || parameters.length == 0) {
      return null;
    }

    // We build manually the JSON for test.parameters tag.
    // Example: {"arguments":{"0":"param1","1":"param2"}}
    final StringBuilder sb = new StringBuilder("{\"arguments\":{");
    for (int i = 0; i < parameters.length; i++) {
      sb.append("\"")
          .append(i)
          .append("\":\"")
          .append(Strings.escapeToJson(String.valueOf(parameters[i])))
          .append("\"");
      if (i != parameters.length - 1) {
        sb.append(",");
      }
    }
    sb.append("}}");
    return sb.toString();
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
      Object parallel =
          XML_TEST_GET_PARALLEL != null
              ? XML_TEST_GET_PARALLEL.invoke(testClass.getXmlTest())
              : null;
      return parallel != null
          && ("methods".equals(parallel.toString()) || "tests".equals(parallel.toString()));
    } catch (Throwable e) {
      return false;
    }
  }

  public static TestIdentifier toTestIdentifier(
      Method method, Object instance, Object[] parameters) {
    Class<?> testClass = instance != null ? instance.getClass() : method.getDeclaringClass();
    String testSuiteName = testClass.getName();
    String testName = method.getName();
    String testParameters = TestNGUtils.getParameters(parameters);
    return new TestIdentifier(testSuiteName, testName, testParameters, null);
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
}
