package datadog.json;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonReaderTest {
  @Test
  void testReadObject() {
    String json =
        "{\"string\":\"bar\",\"int\":3,\"long\":3456789123,\"float\":3.142,\"double\":3.141592653589793,\"true\":true,\"false\":false,\"null\":null}";
    try (JsonReader reader = new JsonReader(json)) {
      reader.beginObject();
      assertEquals("string", reader.nextName());
      assertEquals("bar", reader.nextString());
      assertEquals("int", reader.nextName());
      assertEquals(3, reader.nextInt());
      assertEquals("long", reader.nextName());
      assertEquals(3456789123L, reader.nextLong());
      assertEquals("float", reader.nextName());
      assertEquals(3.142, reader.nextDouble(), 0.001);
      assertEquals("double", reader.nextName());
      assertEquals(3.141592653589793, reader.nextDouble(), 0.000000000000001);
      assertEquals("true", reader.nextName());
      assertTrue(reader.nextBoolean());
      assertEquals("false", reader.nextName());
      assertFalse(reader.nextBoolean());
      assertEquals("null", reader.nextName());
      assertTrue(reader.isNull());
      assertNull(reader.nextValue());
      assertFalse(reader.hasNext());
      reader.endObject();
    } catch (IOException e) {
      fail("Failed to read JSON object", e);
    }
  }

  @Test
  void testReadEmptyObject() {
    String json = "{}";
    try (JsonReader reader = new JsonReader(json)) {
      reader.beginObject();
      reader.endObject();
    } catch (IOException e) {
      fail("Failed to read JSON empty object", e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "null", "1", "[]", "true", "false"})
  void testInvalidObjectStart(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.beginObject();
          }
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"key\":\"value\"}", "{null}", "{]"})
  void testInvalidObjectEnd(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.beginObject();
            reader.endObject();
          }
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"key\"}", "{\"key\"\"value\"}", "{key:\"value\"}"})
  void testInvalidObjectNames(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.beginObject();
            reader.nextName();
          }
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"key\":value}"})
  void testInvalidObjectValue(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.beginObject();
            reader.nextName();
            reader.nextValue();
          }
        });
  }

  @Test
  void testReadArray() {
    String json = "[\"foo\",\"baz\",\"bar\",\"quux\"]";
    try (JsonReader reader = new JsonReader(json)) {
      reader.beginArray();
      assertEquals("foo", reader.nextString());
      assertEquals("baz", reader.nextString());
      assertEquals("bar", reader.nextString());
      assertEquals("quux", reader.nextString());
      assertFalse(reader.hasNext());
      reader.endArray();
    } catch (IOException e) {
      fail("Failed to read JSON array", e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "null", "1", "{}", "true", "false"})
  void testInvalidArrayStart(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.beginArray();
          }
        },
        "Failed to detect invalid array start");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[", "[\"value\"]", "[null", "[}"})
  void testInvalidArrayEnd(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.beginArray();
            reader.endArray();
          }
        },
        "Failed to detect invalid array end");
  }

  @Test
  void testIsNull() {
    try (JsonReader reader = new JsonReader("null")) {
      assertTrue(reader.isNull());
    } catch (IOException e) {
      fail("Failed to read null value JSON", e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"bar\"", "3", "3456789123", "3.142", "true", "false", "{}", "[]"})
  void testIsNotNull(String json) {
    try (JsonReader reader = new JsonReader(json)) {
      assertFalse(reader.isNull());
    } catch (IOException e) {
      fail("Failed to read non-null value JSON", e);
    }
  }

  @Test
  void testReadBoolean() {
    assertDoesNotThrow(
        () -> {
          assertTrue(readBoolean("true"));
          assertFalse(readBoolean("false"));
        },
        "Failed to read boolean value");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ttrue",
        "ffalse",
        "TRUE",
        "FALSE",
        "null",
        "\"bar\"",
        "3",
        "3456789123",
        "3.142",
        "{}",
        "[]"
      })
  void testInvalidBoolean(String json) {
    assertThrows(
        IOException.class, () -> readBoolean(json), "Failed to detect invalid boolean value");
  }

  @Test
  void testStringEscaping() {
    String json = "[\"\\\"\",\"\\\\\",\"\\/\",\"\\b\",\"\\f\",\"\\n\",\"\\r\",\"\\t\",\"\\u00C9\"]";
    try (JsonReader reader = new JsonReader(json)) {
      reader.beginArray();
      assertEquals("\"", reader.nextString());
      assertEquals("\\", reader.nextString());
      assertEquals("/", reader.nextString());
      assertEquals("\b", reader.nextString());
      assertEquals("\f", reader.nextString());
      assertEquals("\n", reader.nextString());
      assertEquals("\r", reader.nextString());
      assertEquals("\t", reader.nextString());
      assertEquals("É", reader.nextString());
      reader.endArray();
    } catch (IOException e) {
      fail("Failed to read escaped JSON strings", e);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "bar",
        "true",
        "false",
        "null",
        "3",
        "3456789123",
        "3.142",
        "{}",
        "[]",
        "\"\\uGHIJ\""
      })
  void testInvalidString(String json) {
    assertThrows(
        IOException.class,
        () -> {
          try (JsonReader reader = new JsonReader(json)) {
            reader.nextString();
          }
        },
        "Failed to detect invalid string value");
  }

  @Test
  void testReadInt() {
    assertDoesNotThrow(
        () -> {
          assertEquals(1, readInt("1"));
          assertEquals(0, readInt("0"));
          assertEquals(-1, readInt("-1"));
          assertEquals(Integer.MAX_VALUE, readInt("2147483647"));
          assertEquals(Integer.MIN_VALUE, readInt("-2147483648"));
        },
        "Failed to read int value");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"\"bar\"", "true", "false", "null", "3456789123", "3.142", "1e100", "{}", "[]"})
  void testInvalidInt(String json) {
    assertThrows(IOException.class, () -> readInt(json), "Failed to detect invalid int value");
  }

  @Test
  void testReadLong() {
    assertDoesNotThrow(
        () -> {
          assertEquals(1L, readLong("1"));
          assertEquals(0L, readLong("0"));
          assertEquals(-1L, readLong("-1"));
          assertEquals(Long.MAX_VALUE, readLong("9223372036854775807"));
          assertEquals(Long.MIN_VALUE, readLong("-9223372036854775808"));
        },
        "Failed to read long value");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"bar\"", "true", "false", "null", "3.142", "1e100", "{}", "[]"})
  void testInvalidLong(String json) {
    assertThrows(IOException.class, () -> readLong(json), "Failed to detect invalid long value");
  }

  @Test
  void testReadDouble() {
    assertDoesNotThrow(
        () -> {
          assertEquals(3.14, readDouble("3.14"), 0.01);
          assertEquals(-3.14, readDouble("-3.14"), 0.01);
          assertEquals(-3.14, readDouble("-3.14e0"), 0.01);
          assertEquals(314, readDouble("3.14e2"), 1);
          assertEquals(0.0314, readDouble("3.14e-2"), 0.0001);
        },
        "Failed to read double value");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"bar\"", "true", "false", "null", "1ee1", "1e", "{}", "[]"})
  void testInvalidDouble(String json) {
    assertThrows(
        IOException.class, () -> readDouble(json), "Failed to detect invalid double value");
  }

  @Test
  void testReaderOnHugePayload() {
    try (InputStream stream = JsonReader.class.getResourceAsStream("/lorem-ipsum.json");
        JsonReader reader = new JsonReader(new InputStreamReader(requireNonNull(stream)))) {
      reader.beginArray();
      while (reader.hasNext()) {
        reader.nextString();
      }
      reader.endArray();
    } catch (IOException e) {
      fail("Failed to read JSON stream", e);
    }
  }

  @Test
  void testConstructor() {
    assertThrows(IllegalArgumentException.class, () -> new JsonReader(null, false).close());
  }

  private boolean readBoolean(String json) throws IOException {
    try (JsonReader reader = new JsonReader(json)) {
      return reader.nextBoolean();
    }
  }

  private int readInt(String json) throws IOException {
    try (JsonReader reader = new JsonReader(json)) {
      return reader.nextInt();
    }
  }

  private long readLong(String json) throws IOException {
    try (JsonReader reader = new JsonReader(json)) {
      return reader.nextLong();
    }
  }

  private double readDouble(String json) throws IOException {
    try (JsonReader reader = new JsonReader(json)) {
      return reader.nextDouble();
    }
  }
}
