package datadog.trace.junit.utils.tabletest;

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

  @TypeConverter
  public static int toInt(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    String token = value.trim();
    switch (token) {
      case "Integer.MAX_VALUE":
        return Integer.MAX_VALUE;
      case "Integer.MIN_VALUE":
        return Integer.MIN_VALUE;
      default:
        return Integer.decode(token);
    }
  }

  @TypeConverter
  public static Number toNumber(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    switch (value) {
      case "Integer.MAX_VALUE":
        return Integer.MAX_VALUE;
      case "Integer.MIN_VALUE":
        return Integer.MIN_VALUE;
      case "Short.MAX_VALUE":
        return Short.MAX_VALUE;
      case "Short.MIN_VALUE":
        return Short.MIN_VALUE;
      case "Float.MAX_VALUE":
        return Float.MAX_VALUE;
      case "Float.MIN_VALUE":
        return Float.MIN_VALUE;
      case "Double.MAX_VALUE":
        return Double.MAX_VALUE;
      case "Double.MIN_VALUE":
        return Double.MIN_VALUE;
      default:
        if (value.endsWith("f")) {
          return Float.parseFloat(value);
        }
        if (value.endsWith("d")) {
          return Double.parseDouble(value);
        }
        return Integer.decode(value);
    }
  }
}
