package datadog.trace.core.otlp.common;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ARRAY;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ARRAY;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ARRAY;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ARRAY;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.GenerationalUtf8Cache;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.SimpleUtf8Cache;
import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Provides optimized writers for OpenTelemetry's "common.proto" wire protocol.
 *
 * <p>Embedded message sizes are precomputed to avoid the need for temporary buffers.
 */
public final class OtlpCommonProto {
  private OtlpCommonProto() {}

  // wire types supported in protobuf v3
  public static final int VARINT_WIRE_TYPE = 0;
  public static final int I64_WIRE_TYPE = 1;
  public static final int LEN_WIRE_TYPE = 2;
  public static final int I32_WIRE_TYPE = 5;

  // use same cache approach for attribute keys as TraceMapperV0_4
  private static final SimpleUtf8Cache KEY_CACHE =
      Config.get().getTagNameUtf8CacheSize() > 0
          ? new SimpleUtf8Cache(Config.get().getTagNameUtf8CacheSize())
          : null;

  // use same cache approach for attribute values as TraceMapperV0_4
  private static final GenerationalUtf8Cache VALUE_CACHE =
      Config.get().getTagValueUtf8CacheSize() > 0
          ? new GenerationalUtf8Cache(Config.get().getTagValueUtf8CacheSize())
          : null;

  public static void recalibrateCaches() {
    VALUE_CACHE.recalibrate();
  }

  public static int sizeVarInt(int value) {
    return 1 + (31 - Integer.numberOfLeadingZeros(value)) / 7;
  }

  public static int sizeVarInt(long value) {
    return 1 + (63 - Long.numberOfLeadingZeros(value)) / 7;
  }

  public static void writeVarInt(ByteBuffer buf, int value) {
    for (int i = 1, len = sizeVarInt(value); i < len; i++) {
      buf.put((byte) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
    buf.put((byte) value);
  }

  public static void writeVarInt(StreamingBuffer buf, int value) {
    for (int i = 1, len = sizeVarInt(value); i < len; i++) {
      buf.put((byte) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
    buf.put((byte) value);
  }

  public static void writeVarInt(StreamingBuffer buf, long value) {
    for (int i = 1, len = sizeVarInt(value); i < len; i++) {
      buf.put((byte) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
    buf.put((byte) value);
  }

  public static void writeI32(StreamingBuffer buf, int value) {
    buf.putInt(Integer.reverseBytes(value)); // convert to little-endian
  }

  public static void writeI32(StreamingBuffer buf, float value) {
    writeI32(buf, Float.floatToRawIntBits(value));
  }

  public static void writeI64(StreamingBuffer buf, long value) {
    buf.putLong(Long.reverseBytes(value)); // convert to little-endian
  }

  public static void writeI64(StreamingBuffer buf, double value) {
    writeI64(buf, Double.doubleToRawLongBits(value));
  }

  public static void writeString(StreamingBuffer buf, byte[] utf8) {
    writeVarInt(buf, utf8.length);
    buf.put(utf8);
  }

  public static void writeString(StreamingBuffer buf, String value) {
    writeString(buf, value.getBytes(UTF_8));
  }

  public static int sizeTag(int fieldNum) {
    return sizeVarInt(fieldNum << 3);
  }

  public static void writeTag(ByteBuffer buf, int fieldNum, int wireType) {
    writeVarInt(buf, fieldNum << 3 | wireType);
  }

  public static void writeTag(StreamingBuffer buf, int fieldNum, int wireType) {
    writeVarInt(buf, fieldNum << 3 | wireType);
  }

  public static byte[] recordMessage(GrowableBuffer buf, int fieldNum) {
    return recordMessage(buf, fieldNum, 0);
  }

  public static byte[] recordMessage(GrowableBuffer buf, int fieldNum, int remainingBytes) {
    try {
      ByteBuffer data = buf.flip();
      int dataSize = data.remaining();
      int expectedSize = dataSize + remainingBytes;
      ByteBuffer message =
          ByteBuffer.allocate(sizeTag(fieldNum) + sizeVarInt(expectedSize) + dataSize);
      writeTag(message, fieldNum, LEN_WIRE_TYPE);
      writeVarInt(message, expectedSize);
      message.put(data);
      return message.array();
    } finally {
      buf.reset();
    }
  }

  public static void writeInstrumentationScope(
      StreamingBuffer buf, OtelInstrumentationScope scope) {
    byte[] nameUtf8 = scope.getName().getUtf8Bytes();
    int scopeSize = 1 + sizeVarInt(nameUtf8.length) + nameUtf8.length;
    byte[] versionUtf8 = null;
    if (scope.getVersion() != null) {
      versionUtf8 = scope.getVersion().getUtf8Bytes();
      scopeSize += 1 + sizeVarInt(versionUtf8.length) + versionUtf8.length;
    }
    writeVarInt(buf, scopeSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeString(buf, nameUtf8);
    if (versionUtf8 != null) {
      writeTag(buf, 2, LEN_WIRE_TYPE);
      writeString(buf, versionUtf8);
    }
  }

  @SuppressWarnings("unchecked")
  public static void writeAttribute(StreamingBuffer buf, int type, CharSequence key, Object value) {
    byte[] keyUtf8;
    if (key instanceof UTF8BytesString) {
      keyUtf8 = ((UTF8BytesString) key).getUtf8Bytes();
    } else {
      keyUtf8 = keyUtf8(key.toString());
    }
    switch (type) {
      case STRING:
        writeStringAttribute(buf, keyUtf8, valueUtf8((String) value));
        break;
      case BOOLEAN:
        writeBooleanAttribute(buf, keyUtf8, (boolean) value);
        break;
      case LONG:
        writeLongAttribute(buf, keyUtf8, ((Number) value).longValue());
        break;
      case DOUBLE:
        writeDoubleAttribute(buf, keyUtf8, ((Number) value).doubleValue());
        break;
      case STRING_ARRAY:
        writeStringArrayAttribute(buf, keyUtf8, (List<String>) value);
        break;
      case BOOLEAN_ARRAY:
        writeBooleanArrayAttribute(buf, keyUtf8, (List<Boolean>) value);
        break;
      case LONG_ARRAY:
        writeLongArrayAttribute(buf, keyUtf8, (List<? extends Number>) value);
        break;
      case DOUBLE_ARRAY:
        writeDoubleArrayAttribute(buf, keyUtf8, (List<? extends Number>) value);
        break;
      default:
        throw new IllegalArgumentException("Unknown attribute type: " + type);
    }
  }

  public static void writeAttribute(
      StreamingBuffer buf, UTF8BytesString key, UTF8BytesString value) {
    writeStringAttribute(buf, key.getUtf8Bytes(), value.getUtf8Bytes());
  }

  public static void writeAttribute(StreamingBuffer buf, UTF8BytesString key, String value) {
    writeStringAttribute(buf, key.getUtf8Bytes(), valueUtf8(value));
  }

  public static void writeAttribute(StreamingBuffer buf, UTF8BytesString key, long value) {
    writeLongAttribute(buf, key.getUtf8Bytes(), value);
  }

  private static byte[] keyUtf8(String key) {
    return KEY_CACHE != null ? KEY_CACHE.getUtf8(key) : key.getBytes(UTF_8);
  }

  private static byte[] valueUtf8(String value) {
    return VALUE_CACHE != null ? VALUE_CACHE.getUtf8(value) : value.getBytes(UTF_8);
  }

  private static void writeStringAttribute(StreamingBuffer buf, byte[] keyUtf8, byte[] valueUtf8) {
    int valueSize = 1 + sizeVarInt(valueUtf8.length) + valueUtf8.length;
    int keyValueSize =
        1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 1 + sizeVarInt(valueSize) + valueSize;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeVarInt(buf, valueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, valueUtf8.length);
    buf.put(valueUtf8);
  }

  private static void writeBooleanAttribute(StreamingBuffer buf, byte[] keyUtf8, boolean value) {
    int keyValueSize = 1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 4;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    buf.put((byte) 2);
    writeTag(buf, 2, VARINT_WIRE_TYPE);
    buf.put((byte) (value ? 1 : 0));
  }

  private static void writeLongAttribute(StreamingBuffer buf, byte[] keyUtf8, long value) {
    int valueSize = 1 + sizeVarInt(value);
    int keyValueSize =
        1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 1 + sizeVarInt(valueSize) + valueSize;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeVarInt(buf, valueSize);
    writeTag(buf, 3, VARINT_WIRE_TYPE);
    writeVarInt(buf, value);
  }

  private static void writeDoubleAttribute(StreamingBuffer buf, byte[] keyUtf8, double value) {
    int keyValueSize = 1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 11;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    buf.put((byte) 9);
    writeTag(buf, 4, I64_WIRE_TYPE);
    writeI64(buf, value);
  }

  private static void writeStringArrayAttribute(
      StreamingBuffer buf, byte[] keyUtf8, List<String> strings) {
    byte[][] valueUtf8s = new byte[strings.size()][];
    for (int i = 0; i < valueUtf8s.length; i++) {
      valueUtf8s[i] = valueUtf8(strings.get(i));
    }
    int arraySize = 0;
    for (byte[] valueUtf8 : valueUtf8s) {
      int elementSize = 1 + sizeVarInt(valueUtf8.length) + valueUtf8.length;
      arraySize += 1 + sizeVarInt(elementSize) + elementSize;
    }
    int valueSize = 1 + sizeVarInt(arraySize) + arraySize;
    int keyValueSize =
        1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 1 + sizeVarInt(valueSize) + valueSize;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeVarInt(buf, valueSize);
    writeTag(buf, 5, LEN_WIRE_TYPE);
    writeVarInt(buf, arraySize);
    for (byte[] valueUtf8 : valueUtf8s) {
      int elementSize = 1 + sizeVarInt(valueUtf8.length) + valueUtf8.length;
      writeTag(buf, 1, LEN_WIRE_TYPE);
      writeVarInt(buf, elementSize);
      writeTag(buf, 1, LEN_WIRE_TYPE);
      writeVarInt(buf, valueUtf8.length);
      buf.put(valueUtf8);
    }
  }

  private static void writeBooleanArrayAttribute(
      StreamingBuffer buf, byte[] keyUtf8, List<Boolean> values) {
    int arraySize = 4 * values.size();
    int valueSize = 1 + sizeVarInt(arraySize) + arraySize;
    int keyValueSize =
        1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 1 + sizeVarInt(valueSize) + valueSize;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeVarInt(buf, valueSize);
    writeTag(buf, 5, LEN_WIRE_TYPE);
    writeVarInt(buf, arraySize);
    for (boolean value : values) {
      writeTag(buf, 1, LEN_WIRE_TYPE);
      buf.put((byte) 2);
      writeTag(buf, 2, VARINT_WIRE_TYPE);
      buf.put((byte) (value ? 1 : 0));
    }
  }

  private static void writeLongArrayAttribute(
      StreamingBuffer buf, byte[] keyUtf8, List<? extends Number> values) {
    long[] longValues = new long[values.size()];
    for (int i = 0; i < longValues.length; i++) {
      longValues[i] = values.get(i).longValue(); // avoid repeated unboxing later
    }
    int arraySize = 0;
    for (long longValue : longValues) {
      int elementSize = 1 + sizeVarInt(longValue);
      arraySize += 1 + sizeVarInt(elementSize) + elementSize;
    }
    int valueSize = 1 + sizeVarInt(arraySize) + arraySize;
    int keyValueSize =
        1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 1 + sizeVarInt(valueSize) + valueSize;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeVarInt(buf, valueSize);
    writeTag(buf, 5, LEN_WIRE_TYPE);
    writeVarInt(buf, arraySize);
    for (long longValue : longValues) {
      int elementSize = 1 + sizeVarInt(longValue);
      writeTag(buf, 1, LEN_WIRE_TYPE);
      writeVarInt(buf, elementSize);
      writeTag(buf, 3, VARINT_WIRE_TYPE);
      writeVarInt(buf, longValue);
    }
  }

  private static void writeDoubleArrayAttribute(
      StreamingBuffer buf, byte[] keyUtf8, List<? extends Number> values) {
    int arraySize = 11 * values.size();
    int valueSize = 1 + sizeVarInt(arraySize) + arraySize;
    int keyValueSize =
        1 + sizeVarInt(keyUtf8.length) + keyUtf8.length + 1 + sizeVarInt(valueSize) + valueSize;
    writeVarInt(buf, keyValueSize);
    writeTag(buf, 1, LEN_WIRE_TYPE);
    writeVarInt(buf, keyUtf8.length);
    buf.put(keyUtf8);
    writeTag(buf, 2, LEN_WIRE_TYPE);
    writeVarInt(buf, valueSize);
    writeTag(buf, 5, LEN_WIRE_TYPE);
    writeVarInt(buf, arraySize);
    for (Number value : values) {
      writeTag(buf, 1, LEN_WIRE_TYPE);
      buf.put((byte) 9);
      writeTag(buf, 4, I64_WIRE_TYPE);
      writeI64(buf, value.doubleValue());
    }
  }
}
