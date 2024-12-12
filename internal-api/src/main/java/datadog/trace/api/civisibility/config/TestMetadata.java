package datadog.trace.api.civisibility.config;

import java.util.Objects;

public class TestMetadata {

  private final boolean missingLineCodeCoverage;

  public TestMetadata(boolean missingLineCodeCoverage) {
    this.missingLineCodeCoverage = missingLineCodeCoverage;
  }

  public boolean isMissingLineCodeCoverage() {
    return missingLineCodeCoverage;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestMetadata that = (TestMetadata) o;
    return missingLineCodeCoverage == that.missingLineCodeCoverage;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(missingLineCodeCoverage);
  }
}
