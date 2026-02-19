package datadog.trace.api.civisibility.events;

import java.util.Objects;
import javax.annotation.Nullable;
import datadog.trace.util.HashingUtils;

public final class TestDescriptor {
  private final String testSuiteName;
  private final @Nullable Class<?> testClass;
  private final String testName;
  private final @Nullable Object testParameters;

  /**
   * A test-framework-specific "tie-breaker" that helps to differentiate between tests that would
   * otherwise be considered identical.
   */
  private final @Nullable Object testQualifier;

  public TestDescriptor(
      String testSuiteName,
      @Nullable Class<?> testClass,
      String testName,
      @Nullable String testParameters,
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
    return HashingUtils.hash(testSuiteName, testClass, testName, testParameters, testQualifier);
  }

  @Override
  public String toString() {
    return "TestDescriptor{"
        + "testSuiteName='"
        + testSuiteName
        + '\''
        + ", testClass="
        + testClass
        + ", testName='"
        + testName
        + '\''
        + ", testParameters='"
        + testParameters
        + '\''
        + ", testQualifier="
        + testQualifier
        + '}';
  }
}
