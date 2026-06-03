package datadog.trace.civisibility;

import datadog.trace.api.civisibility.config.TestFQN;
import org.tabletest.junit.TypeConverter;

/** Shared {@code @TableTest} converters for CiVisibility smoke tests. */
public final class CiVisibilityTableTestConverters {

  private CiVisibilityTableTestConverters() {}

  /** Parses a {@code suite:name} string into a {@link TestFQN}. */
  @TypeConverter
  public static TestFQN toTestFQN(String value) {
    int colon = value.indexOf(':');
    return new TestFQN(value.substring(0, colon), value.substring(colon + 1));
  }
}
