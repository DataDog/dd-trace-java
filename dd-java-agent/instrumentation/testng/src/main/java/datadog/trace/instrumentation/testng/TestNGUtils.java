package datadog.trace.instrumentation.testng;

import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.testng.IClass;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.annotations.Test;
import org.testng.internal.ConstructorOrMethod;
import org.testng.internal.ITestResultNotifier;

public abstract class TestNGUtils {

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
    if (result.getParameters() == null || result.getParameters().length == 0) {
      return null;
    }

    // We build manually the JSON for test.parameters tag.
    // Example: {"arguments":{"0":"param1","1":"param2"}}
    final StringBuilder sb = new StringBuilder("{\"arguments\":{");
    for (int i = 0; i < result.getParameters().length; i++) {
      sb.append("\"")
          .append(i)
          .append("\":\"")
          .append(Strings.escapeToJson(String.valueOf(result.getParameters()[i])))
          .append("\"");
      if (i != result.getParameters().length - 1) {
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
}
