package datadog.json;

import static java.lang.Math.PI;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JsonWriterTest {
  @Test
  void testObject() {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginObject()
          .name("string")
          .value("bar")
          .name("int")
          .value(3)
          .name("long")
          .value(3456789123L)
          .name("float")
          .value(3.142)
          .name("double")
          .value(PI)
          .name("true")
          .value(true)
          .name("false")
          .value(false)
          .name("null")
          .nullValue()
          .endObject();

      assertEquals(
          "{\"string\":\"bar\",\"int\":3,\"long\":3456789123,\"float\":3.142,\"double\":3.141592653589793,\"true\":true,\"false\":false,\"null\":null}",
          writer.toString(),
          "Check object writer");
    }
  }

  @Test
  void testNullName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginObject();
            writer.name(null);
          }
        },
        "Check null name");
  }

  @Test
  void testNullValue() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("string").value(null);
      writer.endObject();
      assertEquals("{\"string\":null}", writer.toString(), "Check null string");
    }
  }

  @Test
  void testNaNValues() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("float").value(Float.NaN);
      writer.name("double").value(Double.NaN);
      writer.endObject();
      assertEquals("{\"float\":null,\"double\":null}", writer.toString(), "Check NaN values");
    }
  }

  @Test
  void testArray() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginArray().value("foo").value("baz").value("bar").value("quux").endArray();
      assertEquals("[\"foo\",\"baz\",\"bar\",\"quux\"]", writer.toString(), "Check array writer");
    }
  }

  @Test
  void testStringEscaping() {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginArray()
          .value("\"")
          .value("\\")
          .value("/")
          .value("\b")
          .value("\f")
          .value("\n")
          .value("\r")
          .value("\t")
          .endArray();

      assertEquals(
          "[\"\\\"\",\"\\\\\",\"\\/\",\"\\b\",\"\\f\",\"\\n\",\"\\r\",\"\\t\"]",
          writer.toString(),
          "Check string escaping");
    }
  }

  @Test
  void testControlCharacterEscaping() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginArray().value("\u0001").value("\u001F").endArray();

      assertEquals(
          "[\"\\u0001\",\"\\u001F\"]", writer.toString(), "Check control character escaping");
    }
  }

  @Test
  void testArrayObjectNesting() {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginObject()
          .name("array")
          .beginArray()
          .value("true")
          .value("false")
          .endArray()
          .endObject();

      assertEquals(
          "{\"array\":[\"true\",\"false\"]}", writer.toString(), "Check array / object nesting");
    }
  }

  @Test
  void testObjectArrayNesting() {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginArray()
          .beginObject()
          .name("true")
          .value(true)
          .endObject()
          .beginObject()
          .name("false")
          .value(false)
          .endObject()
          .endArray();

      assertEquals(
          "[{\"true\":true},{\"false\":false}]", writer.toString(), "Check object / array nesting");
    }
  }

  @Test
  void testNameOnlyInObject() {
    try (JsonWriter writer = new JsonWriter()) {
      assertThrows(
          IllegalStateException.class, () -> writer.name("key"), "Check name only in object");
    }
  }

  @Test
  void testCompleteObject() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.endObject();
      assertThrows(IllegalStateException.class, writer::beginObject, "Check complete object");
      assertThrows(IllegalStateException.class, writer::beginArray, "Check complete object");
    }
  }

  @Test
  void testSizeInBytes() {
    try (JsonWriter writer = new JsonWriter()) {
      assertEquals(0, writer.sizeInBytes(), "Check empty writer size");

      writer.beginArray();
      assertSizeInBytes(writer, "Check size after plain ASCII string");
      writer.value("bar");

      assertSizeInBytes(writer, "Check size after escaped characters");
      writer.value("\"\\/");

      assertSizeInBytes(writer, "Check size after named escapes");
      writer.value("\b\f\n\r\t");

      assertSizeInBytes(writer, "Check size after control character escape");
      writer.value("\u0001");

      assertSizeInBytes(writer, "Check size after non-ASCII character escape");
      writer.value("café");

      assertSizeInBytes(writer, "Check size after int value");
      writer.value(3);

      assertSizeInBytes(writer, "Check size after long value");
      writer.value(3456789123L);

      assertSizeInBytes(writer, "Check size after float value");
      writer.value(3.142f);

      assertSizeInBytes(writer, "Check size after double value");
      writer.value(PI);

      assertSizeInBytes(writer, "Check size after boolean value");
      writer.value(true);

      assertSizeInBytes(writer, "Check size after null value");
      writer.nullValue();

      writer.endArray();
      assertSizeInBytes(writer, "Check final size matches written bytes");
    }
  }

  private static void assertSizeInBytes(JsonWriter writer, String message) {
    assertEquals(writer.toByteArray().length, writer.sizeInBytes(), message);
  }

  @Test
  void testCompleteArray() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginArray();
      writer.endArray();
      assertThrows(IllegalStateException.class, writer::beginObject, "Check complete array");
      assertThrows(IllegalStateException.class, writer::beginArray, "Check complete array");
    }
  }
}
