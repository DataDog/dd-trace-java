package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import munit.MUnitRunner;
import org.junit.runner.Description;

public abstract class MUnitUtils {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(MUnitRunner.class.getClassLoader());
  private static final MethodHandle RUNNER_CREATE_TEST_DESCRIPTION =
      METHOD_HANDLES.method(
          MUnitRunner.class,
          m -> "createTestDescription".equals(m.getName()) && m.getParameterCount() == 1);

  private MUnitUtils() {}

  public static Description createDescription(MUnitRunner runner, Object test) {
    return METHOD_HANDLES.invoke(RUNNER_CREATE_TEST_DESCRIPTION, runner, test);
  }

  public static TestDescriptor toTestDescriptor(Description description) {
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();
    String testName = description.getMethodName();
    return new TestDescriptor(testSuiteName, testClass, testName, null, null);
  }

  public static TestSuiteDescriptor toSuiteDescriptor(Description description) {
    Class<?> testClass = description.getTestClass();
    String testSuiteName = description.getClassName();
    return new TestSuiteDescriptor(testSuiteName, testClass);
  }
}
