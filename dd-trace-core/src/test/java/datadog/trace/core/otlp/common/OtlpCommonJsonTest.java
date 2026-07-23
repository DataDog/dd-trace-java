package datadog.trace.core.otlp.common;

import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.BOOLEAN_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.DOUBLE_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.LONG_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ARRAY_ATTRIBUTE;
import static datadog.trace.bootstrap.otlp.common.OtlpAttributeVisitor.STRING_ATTRIBUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.json.JsonMapper;
import datadog.json.JsonWriter;
import datadog.trace.api.DD128bTraceId;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpCommonJsonTest {

  @Test
  void hexTraceIdEncodesHighAndLowOrderBytes() {
    DD128bTraceId traceId = DD128bTraceId.from(0x0123456789abcdefL, 0xfedcba9876543210L);
    assertEquals("0123456789abcdeffedcba9876543210", OtlpCommonJson.hexTraceId(traceId));
  }

  @Test
  void hexSpanIdEncodes64Bits() {
    assertEquals("0000000000000001", OtlpCommonJson.hexSpanId(1L));
    assertEquals("00000000000004d2", OtlpCommonJson.hexSpanId(1234L));
  }

  @Test
  void stringAttribute() throws IOException {
    Map<String, Object> keyValue = writeAndParseAttribute(STRING_ATTRIBUTE, "k", "v");
    assertEquals("k", keyValue.get("key"));
    assertEquals("v", valueOf(keyValue).get("stringValue"));
  }

  @Test
  void booleanAttribute() throws IOException {
    Map<String, Object> keyValue = writeAndParseAttribute(BOOLEAN_ATTRIBUTE, "flag", true);
    assertEquals(Boolean.TRUE, valueOf(keyValue).get("boolValue"));
  }

  @Test
  void longAttributeIsEncodedAsDecimalString() throws IOException {
    Map<String, Object> keyValue = writeAndParseAttribute(LONG_ATTRIBUTE, "n", 42L);
    assertEquals("42", valueOf(keyValue).get("intValue"));
  }

  @Test
  void doubleAttributeIsEncodedAsNumber() throws IOException {
    Map<String, Object> keyValue = writeAndParseAttribute(DOUBLE_ATTRIBUTE, "d", 3.14);
    assertEquals(3.14, ((Number) valueOf(keyValue).get("doubleValue")).doubleValue());
  }

  @Test
  void stringArrayAttributeWrapsValuesInArrayValue() throws IOException {
    Map<String, Object> keyValue =
        writeAndParseAttribute(STRING_ARRAY_ATTRIBUTE, "tags", Arrays.asList("a", "b"));

    Map<String, Object> value = valueOf(keyValue);
    Map<String, Object> arrayValue = (Map<String, Object>) value.get("arrayValue");
    List<Object> values = (List<Object>) arrayValue.get("values");
    assertEquals(2, values.size());
    assertEquals("a", ((Map<String, Object>) values.get(0)).get("stringValue"));
    assertEquals("b", ((Map<String, Object>) values.get(1)).get("stringValue"));
  }

  @Test
  void longArrayAttributeEncodesEachElementAsDecimalString() throws IOException {
    Map<String, Object> keyValue =
        writeAndParseAttribute(LONG_ARRAY_ATTRIBUTE, "ns", Arrays.asList(1L, 2L, 3L));

    Map<String, Object> arrayValue = (Map<String, Object>) valueOf(keyValue).get("arrayValue");
    List<Object> values = (List<Object>) arrayValue.get("values");
    assertEquals("1", ((Map<String, Object>) values.get(0)).get("intValue"));
    assertEquals("2", ((Map<String, Object>) values.get(1)).get("intValue"));
    assertEquals("3", ((Map<String, Object>) values.get(2)).get("intValue"));
  }

  @Test
  void booleanArrayAttribute() throws IOException {
    Map<String, Object> keyValue =
        writeAndParseAttribute(BOOLEAN_ARRAY_ATTRIBUTE, "flags", Arrays.asList(true, false));

    Map<String, Object> arrayValue = (Map<String, Object>) valueOf(keyValue).get("arrayValue");
    List<Object> values = (List<Object>) arrayValue.get("values");
    assertEquals(Boolean.TRUE, ((Map<String, Object>) values.get(0)).get("boolValue"));
    assertEquals(Boolean.FALSE, ((Map<String, Object>) values.get(1)).get("boolValue"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> valueOf(Map<String, Object> keyValue) {
    return (Map<String, Object>) keyValue.get("value");
  }

  private static Map<String, Object> writeAndParseAttribute(int type, String key, Object value)
      throws IOException {
    try (JsonWriter writer = new JsonWriter()) {
      OtlpCommonJson.writeAttribute(writer, type, key, value);
      return JsonMapper.fromJsonToMap(writer.toString());
    }
  }
}
