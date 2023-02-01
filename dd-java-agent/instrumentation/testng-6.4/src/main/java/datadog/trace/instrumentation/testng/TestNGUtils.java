package datadog.trace.instrumentation.testng;

import datadog.trace.util.Strings;
import java.lang.reflect.Method;
import org.testng.IClass;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.internal.ConstructorOrMethod;

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
}
