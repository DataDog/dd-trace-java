package datadog.trace.core.serialization;

import static datadog.trace.core.StringTables.DURATION;
import static datadog.trace.core.StringTables.ERROR;
import static datadog.trace.core.StringTables.META;
import static datadog.trace.core.StringTables.METRICS;
import static datadog.trace.core.StringTables.NAME;
import static datadog.trace.core.StringTables.PARENT_ID;
import static datadog.trace.core.StringTables.RESOURCE;
import static datadog.trace.core.StringTables.SERVICE;
import static datadog.trace.core.StringTables.SPAN_ID;
import static datadog.trace.core.StringTables.START;
import static datadog.trace.core.StringTables.TRACE_ID;
import static datadog.trace.core.StringTables.TYPE;

import datadog.trace.core.DDSpan;
import datadog.trace.core.StringTables;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public abstract class FormatWriter<DEST> {

  public abstract void writeKey(final byte[] key, final DEST destination) throws IOException;

  public abstract void writeListHeader(final int size, final DEST destination) throws IOException;

  public abstract void writeListFooter(final DEST destination) throws IOException;

  public abstract void writeMapHeader(final int size, final DEST destination) throws IOException;

  public abstract void writeMapFooter(final DEST destination) throws IOException;

  public void writeTag(final byte[] key, final String value, final DEST destination)
      throws IOException {
    writeString(key, value, destination);
  }

  public abstract void writeString(final byte[] key, final String value, final DEST destination)
      throws IOException;

  public abstract void writeShort(final byte[] key, final short value, final DEST destination)
      throws IOException;

  public abstract void writeByte(final byte[] key, final byte value, final DEST destination)
      throws IOException;

  public abstract void writeInt(final byte[] key, final int value, final DEST destination)
      throws IOException;

  public abstract void writeLong(final byte[] key, final long value, final DEST destination)
      throws IOException;

  public abstract void writeFloat(final byte[] key, final float value, final DEST destination)
      throws IOException;

  public abstract void writeDouble(final byte[] key, final double value, final DEST destination)
      throws IOException;

  public abstract void writeBigInteger(
      final byte[] key, final BigInteger value, final DEST destination) throws IOException;

  public void writeNumber(final byte[] key, final Number value, final DEST destination)
      throws IOException {
    if (value instanceof Double) {
      writeDouble(key, value.doubleValue(), destination);
    } else if (value instanceof Long) {
      writeLong(key, value.longValue(), destination);
    } else if (value instanceof Integer) {
      writeInt(key, value.intValue(), destination);
    } else if (value instanceof Float) {
      writeFloat(key, value.floatValue(), destination);
    } else if (value instanceof Byte) {
      writeByte(key, value.byteValue(), destination);
    } else if (value instanceof Short) {
      writeShort(key, value.shortValue(), destination);
    }
  }

  public void writeNumberMap(
      final byte[] key, final Map<String, Number> value, final DEST destination)
      throws IOException {
    writeKey(key, destination);
    writeMapHeader(value.size(), destination);
    for (final Map.Entry<String, Number> entry : value.entrySet()) {
      writeNumber(stringToBytes(entry.getKey()), entry.getValue(), destination);
    }
    writeMapFooter(destination);
  }

  public void writeMeta(final DDSpan span, final DEST destination) throws IOException {
    writeKey(META, destination);
    Map<String, String> baggage = span.context().getBaggageItems();
    Map<String, Object> tags = span.context().getTags();
    writeMapHeader(baggage.size() + tags.size(), destination);
    for (Map.Entry<String, String> entry : baggage.entrySet()) {
      // tags and baggage may intersect, but tags take priority
      if (!tags.containsKey(entry.getKey())) {
        writeTag(stringToBytes(entry.getKey()), entry.getValue(), destination);
      }
    }
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      if (entry.getValue() instanceof String) {
        writeTag(stringToBytes(entry.getKey()), (String) entry.getValue(), destination);
      } else {
        writeString(stringToBytes(entry.getKey()), String.valueOf(entry.getValue()), destination);
      }
    }
    writeMapFooter(destination);
  }

  public void writeTrace(final List<DDSpan> trace, final DEST destination) throws IOException {
    writeListHeader(trace.size(), destination);
    for (final DDSpan span : trace) {
      writeDDSpan(span, destination);
    }
    writeListFooter(destination);
  }

  public void writeDDSpan(final DDSpan span, final DEST destination) throws IOException {
    // Some of the tests rely on the specific ordering here.
    writeMapHeader(12, destination); // must match count below.
    /* 1  */ writeTag(SERVICE, span.getServiceName(), destination);
    /* 2  */ writeString(NAME, span.getOperationName(), destination);
    /* 3  */ writeString(RESOURCE, span.getResourceName(), destination);
    /* 4  */ writeBigInteger(TRACE_ID, span.getTraceId(), destination);
    /* 5  */ writeBigInteger(SPAN_ID, span.getSpanId(), destination);
    /* 6  */ writeBigInteger(PARENT_ID, span.getParentId(), destination);
    /* 7  */ writeLong(START, span.getStartTime(), destination);
    /* 8  */ writeLong(DURATION, span.getDurationNano(), destination);
    /* 9  */ writeTag(TYPE, span.getType(), destination);
    /* 10 */ writeInt(ERROR, span.getError(), destination);
    /* 11 */ writeNumberMap(METRICS, span.getMetrics(), destination);
    /* 12 */ writeMeta(span, destination);
    writeMapFooter(destination);
  }

  private static byte[] stringToBytes(final String string) {
    // won't reassign key or string in this method
    final byte[] key = StringTables.getKeyBytesUTF8(string);
    return null == key ? string.getBytes(StandardCharsets.UTF_8) : key;
  }
}
