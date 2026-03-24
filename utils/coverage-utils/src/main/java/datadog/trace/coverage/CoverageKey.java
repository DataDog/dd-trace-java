package datadog.trace.coverage;

import java.util.Objects;

public class CoverageKey {
  public final String sourceFile;
  public final String className;

  public CoverageKey(String sourceFile, String className) {
    this.sourceFile = sourceFile;
    this.className = className;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CoverageKey that = (CoverageKey) o;
    return Objects.equals(sourceFile, that.sourceFile) && Objects.equals(className, that.className);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceFile, className);
  }
}
