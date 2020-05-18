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

  public abstract void writeKey(byte[] key, DEST destination) throws IOException;

  public abstract void writeListHeader(int size, DEST destination) throws IOException;

  public abstract void writeListFooter(DEST destination) throws IOException;

  public abstract void writeMapHeader(int size, DEST destination) throws IOException;

  public abstract void writeMapFooter(DEST destination) throws IOException;

  public void writeTag(byte[] key, String value, DEST destination) throws IOException {
    writeString(key, value, destination);
  }

  public abstract void writeString(byte[] key, String value, DEST destination) throws IOException;

  public abstract void writeShort(byte[] key, short value, DEST destination) throws IOException;

  public abstract void writeByte(byte[] key, byte value, DEST destination) throws IOException;

  public abstract void writeInt(byte[] key, int value, DEST destination) throws IOException;

  public abstract void writeLong(byte[] key, long value, DEST destination) throws IOException;

  public abstract void writeFloat(byte[] key, float value, DEST destination) throws IOException;

  public abstract void writeDouble(byte[] key, double value, DEST destination) throws IOException;

  public abstract void writeBigInteger(byte[] key, BigInteger value, DEST destination)
      throws IOException;

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

  public void writeStringMap(
      final byte[] key, final Map<String, String> value, final DEST destination)
      throws IOException {
    writeKey(key, destination);
    writeStringMap(value, destination);
  }

  void writeStringMap(final Map<String, String> value, final DEST destination) throws IOException {
    writeMapHeader(value.size(), destination);
    for (final Map.Entry<String, String> entry : value.entrySet()) {
      writeTag(stringToBytes(entry.getKey()), entry.getValue(), destination);
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

  private byte[] stringToBytes(String string) {
    byte[] key = StringTables.getBytesUTF8(string);
    return null == key ? string.getBytes(StandardCharsets.UTF_8) : key;
  }
}
