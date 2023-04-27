package datadog.trace.civisibility.events;

import java.util.Objects;

final class TestDescriptor {
  private final String testSuiteName;
  private final Class<?> testClass;
  private final String testName;
  private final String testParameters;

  TestDescriptor(String testSuiteName, Class<?> testClass, String testName, String testParameters) {
    this.testSuiteName = testSuiteName;
    this.testClass = testClass;
    this.testName = testName;
    this.testParameters = testParameters;
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
        && Objects.equals(testParameters, that.testParameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(testSuiteName, testClass, testName, testParameters);
  }
}
