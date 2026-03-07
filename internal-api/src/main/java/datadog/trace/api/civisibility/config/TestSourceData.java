package datadog.trace.api.civisibility.config;

import java.lang.reflect.Method;
import java.util.Objects;
import javax.annotation.Nullable;
import datadog.trace.util.HashingUtils;

/** Data needed to identify test definition source. */
public class TestSourceData {

  public static final TestSourceData UNKNOWN = new TestSourceData(null, null);

  @Nullable private final Class<?> testClass;

  @Nullable private final Method testMethod;

  /**
   * The name of the test method. May not correspond to {@code testMethod.getName()} (for instance,
   * in Spock the testMethod is generated at compile time and has a name that is different from the
   * source code method name)
   */
  @Nullable private final String testMethodName;

  public TestSourceData(@Nullable Class<?> testClass, @Nullable Method testMethod) {
    this(testClass, testMethod, testMethod != null ? testMethod.getName() : null);
  }

  public TestSourceData(
      @Nullable Class<?> testClass, @Nullable Method testMethod, @Nullable String testMethodName) {
    this.testClass = testClass;
    this.testMethod = testMethod;
    this.testMethodName = testMethodName;
  }

  @Nullable
  public Class<?> getTestClass() {
    return testClass;
  }

  @Nullable
  public Method getTestMethod() {
    return testMethod;
  }

  /**
   * Returns the name of the test method. May not correspond to {@code testMethod.getName()} (for
   * instance, in Spock the testMethod is generated at compile time and has a name that is different
   * from the source code method name)
   */
  @Nullable
  public String getTestMethodName() {
    return testMethodName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestSourceData that = (TestSourceData) o;
    return Objects.equals(testClass, that.testClass)
        && Objects.equals(testMethod, that.testMethod)
        && Objects.equals(testMethodName, that.testMethodName);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(testClass, testMethod, testMethodName);
  }

  @Override
  public String toString() {
    return "TestSourceData{"
        + "testClass="
        + testClass
        + ", testMethod="
        + testMethod
        + ", testMethodName="
        + testMethodName
        + '}';
  }
}
