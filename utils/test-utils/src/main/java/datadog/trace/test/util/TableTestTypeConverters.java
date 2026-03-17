package datadog.trace.test.util;

import org.tabletest.junit.TypeConverter;

/** Shared converters for JUnit 5 TableTest tests that use unparsable constants. */
public final class TableTestTypeConverters {

  private TableTestTypeConverters() {}

  @TypeConverter
  public static long toLong(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    String token = value.trim();
    switch (token) {
      case "Long.MAX_VALUE":
        return Long.MAX_VALUE;
      case "Long.MIN_VALUE":
        return Long.MIN_VALUE;
      default:
        return Long.decode(token);
    }
  }
}
