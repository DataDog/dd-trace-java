package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

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
    return HashingUtils.hash(name, version);
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

  public static void serialize(Serializer serializer, TestFramework testFramework) {
    serializer.write(testFramework.name);
    serializer.write(testFramework.version);
  }

  public static TestFramework deserialize(ByteBuffer buf) {
    return new TestFramework(Serializer.readString(buf), Serializer.readString(buf));
  }
}
