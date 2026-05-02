package datadog.trace.api;

import datadog.trace.junit.utils.tabletest.TableTestTypeConverters;
import org.tabletest.junit.TypeConverter;

/** TableTest converters shared by dd-trace-api test classes for unparsable constants. */
public final class DDTraceApiTableTestConverters {

  private DDTraceApiTableTestConverters() {}

  @TypeConverter
  public static long toLong(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    String token = value.trim();
    switch (token) {
      case "DDSpanId.MAX":
        return DDSpanId.MAX;
      case "DDSpanId.ZERO":
        return DDSpanId.ZERO;
      default:
        return TableTestTypeConverters.toLong(token);
    }
  }

  @TypeConverter
  public static DD64bTraceId toDD64bTraceId(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    switch (value.trim()) {
      case "DD64bTraceId.ZERO":
        return DD64bTraceId.from(0L);
      case "DD64bTraceId.ONE":
        return DD64bTraceId.from(1L);
      case "DD64bTraceId.MAX":
        return DD64bTraceId.MAX;
      case "DD64bTraceId.LONG_MAX":
        return DD64bTraceId.from(Long.MAX_VALUE);
      case "DD64bTraceId.LONG_MIN":
        return DD64bTraceId.from(Long.MIN_VALUE);
      case "DD64bTraceId.CAFEBABE":
        return DD64bTraceId.from(3405691582L);
      case "DD64bTraceId.HEX":
        return DD64bTraceId.from(81985529216486895L);
      default:
        throw new IllegalArgumentException("Unsupported DD64bTraceId token: " + value);
    }
  }
}
