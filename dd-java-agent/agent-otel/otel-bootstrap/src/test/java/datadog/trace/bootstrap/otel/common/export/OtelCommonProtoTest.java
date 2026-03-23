package datadog.trace.bootstrap.otel.common.export;

import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.BOOLEAN;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.BOOLEAN_ARRAY;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.DOUBLE;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.DOUBLE_ARRAY;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.LONG;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.LONG_ARRAY;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.STRING;
import static datadog.trace.bootstrap.otel.common.export.OtelAttributeVisitor.STRING_ARRAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.communication.serialization.GrowableBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OtelCommonProto#writeAttribute}.
 *
 * <p>Each test creates a {@link GrowableBuffer}, calls {@code writeAttribute} with a given type,
 * key, and value, then extracts the byte array and verifies its content against the OpenTelemetry
 * {@code KeyValue} protobuf encoding defined in {@code opentelemetry/proto/common/v1/common.proto}.
 *
 * <p>Relevant proto field numbers:
 *
 * <pre>
 *   KeyValue   { string key = 1; AnyValue value = 2; }
 *   AnyValue   { string string_value = 1; bool bool_value = 2; int64 int_value = 3;
 *                double double_value = 4; ArrayValue array_value = 5; }
 *   ArrayValue { repeated AnyValue values = 1; }
 * </pre>
 */
class OtelCommonProtoTest {

  // ── encoding helpers ──────────────────────────────────────────────────────

  private static byte[] encode(int type, String key, Object value) {
    GrowableBuffer buf = new GrowableBuffer(256);
    OtelCommonProto.writeAttribute(buf, type, key, value);
    ByteBuffer slice = buf.slice();
    byte[] bytes = new byte[slice.remaining()];
    slice.get(bytes);
    return bytes;
  }

  /**
   * {@code writeAttribute} prepends the {@code KeyValue} body with its byte size as a varint.
   * Remove that prefix and return a {@link CodedInputStream} over the {@code KeyValue} body.
   */
  private static CodedInputStream keyValueStream(byte[] bytes) throws IOException {
    CodedInputStream outer = CodedInputStream.newInstance(bytes);
    int kvSize = outer.readRawVarint32();
    int varintSize = bytes.length - kvSize;
    return CodedInputStream.newInstance(Arrays.copyOfRange(bytes, varintSize, bytes.length));
  }

  // ── proto parsing helpers ─────────────────────────────────────────────────

  /** Reads the {@code KeyValue.key} field (field 1, LEN) and returns the string value. */
  private static String readKeyField(CodedInputStream kv) throws IOException {
    int tag = kv.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "KeyValue.key is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    return kv.readString();
  }

  /**
   * Reads the {@code KeyValue.value} field (field 2, LEN) and returns a stream over the {@code
   * AnyValue} body.
   */
  private static CodedInputStream readAnyValueField(CodedInputStream kv) throws IOException {
    int tag = kv.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "KeyValue.value is field 2");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    return kv.readBytes().newCodedInput();
  }

  /**
   * Reads the {@code AnyValue.array_value} field (field 5, LEN) and returns a stream over the
   * {@code ArrayValue} body.
   */
  private static CodedInputStream readArrayValueField(CodedInputStream av) throws IOException {
    int tag = av.readTag();
    assertEquals(5, WireFormat.getTagFieldNumber(tag), "AnyValue.array_value is field 5");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    return av.readBytes().newCodedInput();
  }

  /**
   * Reads one {@code ArrayValue.values} element (field 1, LEN) and returns a stream over the
   * element's {@code AnyValue} body.
   */
  private static CodedInputStream readArrayElement(CodedInputStream arr) throws IOException {
    int tag = arr.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "ArrayValue.values is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    return arr.readBytes().newCodedInput();
  }

  // ── scalar attribute tests ────────────────────────────────────────────────

  @Test
  void testStringAttribute() throws IOException {
    byte[] bytes = encode(STRING, "str-key", "hello");
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("str-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "AnyValue.string_value is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertEquals("hello", av.readString());
  }

  @Test
  void testBooleanAttributeTrue() throws IOException {
    byte[] bytes = encode(BOOLEAN, "bool-key", true);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("bool-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "AnyValue.bool_value is field 2");
    assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
    assertTrue(av.readBool());
  }

  @Test
  void testBooleanAttributeFalse() throws IOException {
    byte[] bytes = encode(BOOLEAN, "bool-key", false);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("bool-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "AnyValue.bool_value is field 2");
    assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
    assertFalse(av.readBool());
  }

  @Test
  void testLongAttribute() throws IOException {
    byte[] bytes = encode(LONG, "long-key", 42L);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("long-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(3, WireFormat.getTagFieldNumber(tag), "AnyValue.int_value is field 3");
    assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
    assertEquals(42L, av.readInt64());
  }

  @Test
  void testDoubleAttribute() throws IOException {
    byte[] bytes = encode(DOUBLE, "dbl-key", 3.14);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("dbl-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(4, WireFormat.getTagFieldNumber(tag), "AnyValue.double_value is field 4");
    assertEquals(WireFormat.WIRETYPE_FIXED64, WireFormat.getTagWireType(tag));
    assertEquals(3.14, av.readDouble(), 0.0);
  }

  // ── array attribute tests ─────────────────────────────────────────────────

  @Test
  void testStringArrayAttribute() throws IOException {
    byte[] bytes = encode(STRING_ARRAY, "arr-str", Arrays.asList("alpha", "beta", "gamma"));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-str", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (String expected : new String[] {"alpha", "beta", "gamma"}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(1, WireFormat.getTagFieldNumber(tag), "AnyValue.string_value is field 1");
      assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
      assertEquals(expected, elem.readString());
    }
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testBooleanArrayAttribute() throws IOException {
    byte[] bytes = encode(BOOLEAN_ARRAY, "arr-bool", Arrays.asList(true, false, true));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-bool", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (boolean expected : new boolean[] {true, false, true}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(2, WireFormat.getTagFieldNumber(tag), "AnyValue.bool_value is field 2");
      assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
      assertEquals(expected, elem.readBool());
    }
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testLongArrayAttribute() throws IOException {
    byte[] bytes = encode(LONG_ARRAY, "arr-long", Arrays.asList(10L, 20L, 30L));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-long", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (long expected : new long[] {10L, 20L, 30L}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(3, WireFormat.getTagFieldNumber(tag), "AnyValue.int_value is field 3");
      assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
      assertEquals(expected, elem.readInt64());
    }
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testDoubleArrayAttribute() throws IOException {
    byte[] bytes = encode(DOUBLE_ARRAY, "arr-dbl", Arrays.asList(1.1, 2.2, 3.3));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-dbl", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (double expected : new double[] {1.1, 2.2, 3.3}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(4, WireFormat.getTagFieldNumber(tag), "AnyValue.double_value is field 4");
      assertEquals(WireFormat.WIRETYPE_FIXED64, WireFormat.getTagWireType(tag));
      assertEquals(expected, elem.readDouble(), 0.0);
    }
    assertTrue(arr.isAtEnd());
  }
}
