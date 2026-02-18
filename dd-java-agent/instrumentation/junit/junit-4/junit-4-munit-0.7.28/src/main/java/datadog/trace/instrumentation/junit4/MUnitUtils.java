package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.MethodHandles;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import munit.MUnitRunner;
import munit.Tag;
import org.junit.runner.Description;

public abstract class MUnitUtils {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(MUnitRunner.class.getClassLoader());
  private static final MethodHandle RUNNER_CREATE_TEST_DESCRIPTION =
      METHOD_HANDLES.method(
          MUnitRunner.class,
          m -> "createTestDescription".equals(m.getName()) && m.getParameterCount() == 1);

  public static final List<LibraryCapability> CAPABILITIES =
      Arrays.asList(
          LibraryCapability.ATR,
          LibraryCapability.EFD,
          LibraryCapability.IMPACTED,
          LibraryCapability.FTR,
          LibraryCapability.QUARANTINE,
          LibraryCapability.ATTEMPT_TO_FIX);

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

  public static List<String> getCategories(Description description) {
    List<String> categories = new ArrayList<>();
    for (Annotation annotation : description.getAnnotations()) {
      if (annotation.annotationType() == Tag.class) {
        Tag tag = (Tag) annotation;
        categories.add(tag.value());
      }
    }
    return categories;
  }
}
