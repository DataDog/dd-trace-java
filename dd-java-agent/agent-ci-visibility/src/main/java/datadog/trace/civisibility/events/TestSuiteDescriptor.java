package datadog.trace.civisibility.events;

import java.util.Objects;

final class TestSuiteDescriptor {
  private final String testSuiteName;
  private final Class<?> testClass;

  TestSuiteDescriptor(String testSuiteName, Class<?> testClass) {
    this.testSuiteName = testSuiteName;
    this.testClass = testClass;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestSuiteDescriptor that = (TestSuiteDescriptor) o;
    return Objects.equals(testSuiteName, that.testSuiteName)
        && Objects.equals(testClass, that.testClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(testSuiteName, testClass);
  }
}
