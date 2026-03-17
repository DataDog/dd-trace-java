package datadog.trace.test.util;

import org.tabletest.junit.TypeConverter;

/** Shared converters for TableTest numeric cells, including symbolic constants. */
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
      case "DDSpanId.MAX":
        return -1L;
      case "DDSpanId.ZERO":
        return 0L;
      default:
        return Long.decode(token);
    }
  }
}
