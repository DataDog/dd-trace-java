package datadog.trace.civisibility.events;

import java.util.Objects;
import javax.annotation.Nullable;

final class TestDescriptor {
  private final String testSuiteName;
  private final Class<?> testClass;
  private final String testName;
  private final String testParameters;

  /**
   * A test-framework-specific "tie-breaker" that helps to differentiate between tests that would
   * otherwise be considered identical.
   */
  private final @Nullable Object testQualifier;

  TestDescriptor(
      String testSuiteName,
      Class<?> testClass,
      String testName,
      String testParameters,
      @Nullable Object testQualifier) {
    this.testSuiteName = testSuiteName;
    this.testClass = testClass;
    this.testName = testName;
    this.testParameters = testParameters;
    this.testQualifier = testQualifier;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestDescriptor that = (TestDescriptor) o;
    return Objects.equals(testSuiteName, that.testSuiteName)
        && Objects.equals(testClass, that.testClass)
        && Objects.equals(testName, that.testName)
        && Objects.equals(testParameters, that.testParameters)
        && Objects.equals(testQualifier, that.testQualifier);
  }

  @Override
  public int hashCode() {
    return Objects.hash(testSuiteName, testClass, testName, testParameters, testQualifier);
  }
}
