package datadog.trace.civisibility.ipc;

import java.util.Objects;

public final class TestFramework implements Comparable<TestFramework> {
  private final String name;
  private final String version;

  public TestFramework(String name, String version) {
    this.name = name;
    this.version = version;
  }

  public String getName() {
    return name;
  }

  public String getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestFramework that = (TestFramework) o;
    return Objects.equals(name, that.name) && Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, version);
  }

  @Override
  public int compareTo(TestFramework o) {
    int nameComparison = name.compareTo(o.name);
    if (nameComparison != 0) {
      return nameComparison;
    }
    if (version == null && o.version == null) {
      return 0;
    }
    if (version == null) {
      return -1;
    }
    if (o.version == null) {
      return 1;
    }
    return version.compareTo(o.version);
  }
}
