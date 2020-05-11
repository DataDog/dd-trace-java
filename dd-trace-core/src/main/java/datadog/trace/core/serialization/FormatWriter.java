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
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public abstract class FormatWriter<DEST> {
  public abstract void writeKey(String key, DEST destination) throws IOException;

  public abstract void writeListHeader(int size, DEST destination) throws IOException;

  public abstract void writeListFooter(DEST destination) throws IOException;

  public abstract void writeMapHeader(int size, DEST destination) throws IOException;

  public abstract void writeMapFooter(DEST destination) throws IOException;

  public void writeTag(String key, String value, DEST destination) throws IOException {
    writeString(key, value, destination);
  }

  public abstract void writeString(String key, String value, DEST destination) throws IOException;

  public abstract void writeShort(String key, short value, DEST destination) throws IOException;

  public abstract void writeByte(String key, byte value, DEST destination) throws IOException;

  public abstract void writeInt(String key, int value, DEST destination) throws IOException;

  public abstract void writeLong(String key, long value, DEST destination) throws IOException;

  public abstract void writeFloat(String key, float value, DEST destination) throws IOException;

  public abstract void writeDouble(String key, double value, DEST destination) throws IOException;

  public abstract void writeBigInteger(String key, BigInteger value, DEST destination)
      throws IOException;

  public void writeNumber(final String key, final Number value, final DEST destination)
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
      final String key, final Map<String, Number> value, final DEST destination)
      throws IOException {
    writeKey(key, destination);
    writeMapHeader(value.size(), destination);
    for (final Map.Entry<String, Number> entry : value.entrySet()) {
      writeNumber(entry.getKey(), entry.getValue(), destination);
    }
    writeMapFooter(destination);
  }

  public void writeStringMap(
      final String key, final Map<String, String> value, final DEST destination)
      throws IOException {
    writeKey(key, destination);
    writeMapHeader(value.size(), destination);
    for (final Map.Entry<String, String> entry : value.entrySet()) {
      writeString(entry.getKey(), entry.getValue(), destination);
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
    /* 1  */ writeString(SERVICE, span.getServiceName(), destination);
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
    /* 12 */ writeStringMap(META, span.getMeta(), destination);
    writeMapFooter(destination);
  }
}
