package datadog.trace.bootstrap.otel.common.export;

import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.BOOLEAN;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.BOOLEAN_ARRAY;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.DOUBLE;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.DOUBLE_ARRAY;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.LONG;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.LONG_ARRAY;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.STRING;
import static datadog.trace.bootstrap.otel.common.export.OtlpAttributeVisitor.STRING_ARRAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.communication.serialization.GrowableBuffer;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link OtlpCommonProto#writeAttribute} and {@link
 * OtlpCommonProto#writeInstrumentationScope}.
 *
 * <p>Each test creates a {@link GrowableBuffer}, calls the method under test, then extracts the
 * byte array and verifies its content against the OpenTelemetry protobuf encoding defined in {@code
 * opentelemetry/proto/common/v1/common.proto}.
 *
 * <p>Relevant proto field numbers:
 *
 * <pre>
 *   InstrumentationScope { string name = 1; string version = 2; }
 *   KeyValue   { string key = 1; AnyValue value = 2; }
 *   AnyValue   { string string_value = 1; bool bool_value = 2; int64 int_value = 3;
 *                double double_value = 4; ArrayValue array_value = 5; }
 *   ArrayValue { repeated AnyValue values = 1; }
 * </pre>
 */
class OtlpCommonProtoTest {

  // ── encoding helpers ──────────────────────────────────────────────────────

  private static byte[] encode(int type, String key, Object value) {
    GrowableBuffer buf = new GrowableBuffer(256);
    OtlpCommonProto.writeAttribute(buf, type, key, value);
    ByteBuffer slice = buf.slice();
    byte[] bytes = new byte[slice.remaining()];
    slice.get(bytes);
    return bytes;
  }

  private static byte[] encodeScope(OtelInstrumentationScope scope) {
    GrowableBuffer buf = new GrowableBuffer(256);
    OtlpCommonProto.writeInstrumentationScope(buf, scope);
    ByteBuffer slice = buf.slice();
    byte[] bytes = new byte[slice.remaining()];
    slice.get(bytes);
    return bytes;
  }

  /**
   * {@code writeInstrumentationScope} prepends the {@code InstrumentationScope} body with its byte
   * size as a varint. Remove that prefix and return a {@link CodedInputStream} over the body.
   */
  private static CodedInputStream instrumentationScopeStream(byte[] bytes) throws IOException {
    CodedInputStream outer = CodedInputStream.newInstance(bytes);
    int scopeSize = outer.readRawVarint32();
    int varintSize = bytes.length - scopeSize;
    return CodedInputStream.newInstance(Arrays.copyOfRange(bytes, varintSize, bytes.length));
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

  // ── instrumentation scope tests ───────────────────────────────────────────

  @Test
  void testInstrumentationScopeWithVersion() throws IOException {
    byte[] bytes = encodeScope(new OtelInstrumentationScope("io.opentelemetry", "1.2.3", null));
    CodedInputStream scope = instrumentationScopeStream(bytes);

    int tag = scope.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "InstrumentationScope.name is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertEquals("io.opentelemetry", scope.readString());

    tag = scope.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "InstrumentationScope.version is field 2");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertEquals("1.2.3", scope.readString());

    assertTrue(scope.isAtEnd());
  }

  @Test
  void testInstrumentationScopeWithNullVersion() throws IOException {
    byte[] bytes = encodeScope(new OtelInstrumentationScope("io.opentelemetry", null, null));
    CodedInputStream scope = instrumentationScopeStream(bytes);

    int tag = scope.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "InstrumentationScope.name is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertEquals("io.opentelemetry", scope.readString());

    assertTrue(scope.isAtEnd(), "version field must be absent when null");
  }

  // ── scalar attribute tests ────────────────────────────────────────────────

  @ParameterizedTest
  @ValueSource(strings = {"hello", "", "héllo", "日本語", "emoji 🎉"})
  void testStringAttribute(String value) throws IOException {
    byte[] bytes = encode(STRING, "str-key", value);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("str-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "AnyValue.string_value is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    assertEquals(value, av.readString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testBooleanAttribute(boolean value) throws IOException {
    byte[] bytes = encode(BOOLEAN, "bool-key", value);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("bool-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "AnyValue.bool_value is field 2");
    assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
    assertEquals(value, av.readBool());
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, 1L, -1L, 42L, Long.MIN_VALUE, Long.MAX_VALUE})
  void testLongAttribute(long value) throws IOException {
    byte[] bytes = encode(LONG, "long-key", value);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("long-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(3, WireFormat.getTagFieldNumber(tag), "AnyValue.int_value is field 3");
    assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
    assertEquals(value, av.readInt64());
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {
        0.0,
        1.0,
        -1.5,
        3.14,
        Double.MIN_VALUE,
        Double.MAX_VALUE,
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY
      })
  void testDoubleAttribute(double value) throws IOException {
    byte[] bytes = encode(DOUBLE, "dbl-key", value);
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("dbl-key", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    int tag = av.readTag();
    assertEquals(4, WireFormat.getTagFieldNumber(tag), "AnyValue.double_value is field 4");
    assertEquals(WireFormat.WIRETYPE_FIXED64, WireFormat.getTagWireType(tag));
    // use raw bits to correctly handle NaN and negative zero
    assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(av.readDouble()));
  }

  // ── array attribute tests ─────────────────────────────────────────────────

  @Test
  void testEmptyStringArrayAttribute() throws IOException {
    byte[] bytes = encode(STRING_ARRAY, "arr-str", Collections.emptyList());
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-str", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testStringArrayAttribute() throws IOException {
    byte[] bytes = encode(STRING_ARRAY, "arr-str", Arrays.asList("alpha", "héllo", "日本語"));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-str", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (String expected : new String[] {"alpha", "héllo", "日本語"}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(1, WireFormat.getTagFieldNumber(tag), "AnyValue.string_value is field 1");
      assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
      assertEquals(expected, elem.readString());
    }
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testEmptyBooleanArrayAttribute() throws IOException {
    byte[] bytes = encode(BOOLEAN_ARRAY, "arr-bool", Collections.emptyList());
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-bool", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);
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
  void testEmptyLongArrayAttribute() throws IOException {
    byte[] bytes = encode(LONG_ARRAY, "arr-long", Collections.emptyList());
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-long", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testLongArrayAttribute() throws IOException {
    byte[] bytes =
        encode(LONG_ARRAY, "arr-long", Arrays.asList(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-long", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (long expected : new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(3, WireFormat.getTagFieldNumber(tag), "AnyValue.int_value is field 3");
      assertEquals(WireFormat.WIRETYPE_VARINT, WireFormat.getTagWireType(tag));
      assertEquals(expected, elem.readInt64());
    }
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testEmptyDoubleArrayAttribute() throws IOException {
    byte[] bytes = encode(DOUBLE_ARRAY, "arr-dbl", Collections.emptyList());
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-dbl", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);
    assertTrue(arr.isAtEnd());
  }

  @Test
  void testDoubleArrayAttribute() throws IOException {
    byte[] bytes =
        encode(
            DOUBLE_ARRAY,
            "arr-dbl",
            Arrays.asList(0.0, -1.5, Double.NaN, Double.POSITIVE_INFINITY));
    CodedInputStream kv = keyValueStream(bytes);

    assertEquals("arr-dbl", readKeyField(kv));

    CodedInputStream av = readAnyValueField(kv);
    CodedInputStream arr = readArrayValueField(av);

    for (double expected : new double[] {0.0, -1.5, Double.NaN, Double.POSITIVE_INFINITY}) {
      CodedInputStream elem = readArrayElement(arr);
      int tag = elem.readTag();
      assertEquals(4, WireFormat.getTagFieldNumber(tag), "AnyValue.double_value is field 4");
      assertEquals(WireFormat.WIRETYPE_FIXED64, WireFormat.getTagWireType(tag));
      // use raw bits to correctly handle NaN and negative zero
      assertEquals(
          Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(elem.readDouble()));
    }
    assertTrue(arr.isAtEnd());
  }
}
