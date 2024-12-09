package datadog.json;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SafeJsonWriterTest {
  @Test
  void testRootElement() {
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginObject().endObject();
          }
        },
        "Check object allowed as root element");
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginObject().beginArray();
          }
        },
        "Check array allowed as root element");
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.value("string");
          }
        },
        "Check string allowed as root element");
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.value(1);
          }
        },
        "Check number allowed as root element");
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.value(true);
          }
        },
        "Check boolean allowed as root element");
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.nullValue();
          }
        },
        "Check null value allowed as root element");
  }

  @Test
  void testNestedElements() {
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer
                .beginObject()
                .beginObject()
                .beginObject()
                .endObject()
                .beginObject()
                .endObject()
                .endObject()
                .endObject();
          }
        },
        "Check nested objects");
    assertDoesNotThrow(
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer
                .beginArray()
                .beginArray()
                .beginArray()
                .endArray()
                .beginArray()
                .endArray()
                .endArray()
                .endArray();
          }
        },
        "Check nested arrays");

    assertThrows(
        IllegalStateException.class,
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginObject().beginObject().endObject().endArray();
          }
        },
        "Check invalid array end");
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginArray().beginArray().endArray().endObject();
          }
        },
        "Check invalid object end");
  }

  @Test
  void testCompleteJson() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginObject().endObject().value("invalid");
          }
        },
        "Check complete object");
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.beginArray().endArray().value("invalid");
          }
        },
        "Check complete array");
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (JsonWriter writer = new JsonWriter()) {
            writer.value("string").value("invalid");
          }
        },
        "Check complete value");
  }
}
