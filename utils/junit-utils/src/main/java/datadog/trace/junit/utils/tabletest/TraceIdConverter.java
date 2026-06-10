package datadog.trace.junit.utils.tabletest;

import static java.math.BigInteger.ONE;

import datadog.trace.api.DDTraceId;
import java.math.BigInteger;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

/**
 * Converts symbolic trace/span ID names to their decimal string representation.
 *
 * <p>Supported names:
 *
 * <ul>
 *   <li>{@code TRACE_ID_MAX} — max unsigned 64-bit value (18446744073709551615 = 2⁶⁴ − 1)
 *   <li>{@code TRACE_ID_MAX-1} — max minus one (18446744073709551614 = 2⁶⁴ − 2)
 *   <li>{@code TRACE_ID_MAX+1} — first out-of-range value (18446744073709551616 = 2⁶⁴)
 * </ul>
 *
 * <p>All other values are passed through unchanged.
 */
public class TraceIdConverter implements ArgumentConverter {
  // 2^64 - 1
  public static final String TRACE_ID_MAX = Long.toUnsignedString(-1L);
  // 2^64 - 2
  public static final String TRACE_ID_MAX_MINUS_1 = Long.toUnsignedString(-2L);
  // 2^64 (first out-of-range value)
  public static final String TRACE_ID_MAX_PLUS_1 = new BigInteger(TRACE_ID_MAX).add(ONE).toString();

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) {
      return null;
    }
    String s = source.toString();
    String traceId;
    switch (s) {
      case "TRACE_ID_MAX":
        traceId = TRACE_ID_MAX;
        break;
      case "TRACE_ID_MAX-1":
        traceId = TRACE_ID_MAX_MINUS_1;
        break;
      case "TRACE_ID_MAX+1":
        traceId = TRACE_ID_MAX_PLUS_1;
        break;
      default:
        traceId = s;
    }

    Class<?> parameterType = context.getParameter().getType();
    if (parameterType.isAssignableFrom(DDTraceId.class)) {
      return DDTraceId.from(s);
    } else if (parameterType.isAssignableFrom(Long.class)) {
      return DDTraceId.from(s).toLong();
    } else {
      return traceId;
    }
  }
}
