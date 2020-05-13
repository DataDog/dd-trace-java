package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecordingTest {
  private Recording recording;

  @BeforeEach
  void setUp() {
    recording = new Recording();
  }

  @Test
  void newChunk() {
    Chunk chunk = recording.newChunk();
    assertNotNull(chunk);

    Chunk chunk1 = recording.newChunk();
    assertNotNull(chunk1);

    assertNotEquals(chunk, chunk1);
  }

  @Test
  void registerEventTypeNullName() {
    assertThrows(IllegalArgumentException.class, () -> recording.registerEventType(null));
  }

  @Test
  void registerEventTypeNullCallback() {
    assertThrows(IllegalArgumentException.class, () -> recording.registerEventType("name", null));
  }

  @Test
  void registerEventCallaback() {
    String name = "custom.Event";
    String fieldName = "field";
    Type eventType =
        recording.registerEventType(
            name,
            t -> {
              t.addField(fieldName, Types.Builtin.STRING);
            });
    assertNotNull(eventType);
    assertEquals(name, eventType.getTypeName());
    assertEquals("jdk.jfr.Event", eventType.getSupertype());
    assertNotNull(eventType.getField(fieldName));
  }

  @Test
  void registerEventTypeNew() {
    String name = "custom.Event";
    Type eventType = recording.registerEventType(name);
    assertNotNull(eventType);
    assertEquals(name, eventType.getTypeName());
    assertEquals("jdk.jfr.Event", eventType.getSupertype());
  }

  @Test
  void registerEventTypeExisting() {
    String name = "custom.Event";
    Type eventType = recording.registerEventType(name);

    Type eventType1 = recording.registerEventType(name);

    assertEquals(eventType, eventType1);
  }

  @Test
  void registerAnnotationTypeNullName() {
    assertThrows(IllegalArgumentException.class, () -> recording.registerAnnotationType(null));
  }

  @Test
  void registerAnnotationTypeNullCallback() {
    assertThrows(
        IllegalArgumentException.class, () -> recording.registerAnnotationType("name", null));
  }

  @Test
  void registerAnnotationTypeWithCallback() {
    String name = "custom.Annotation";
    String fieldName = "field";
    Type annotationType =
        recording.registerAnnotationType(
            name,
            t -> {
              t.addField(fieldName, Types.Builtin.STRING);
            });
    assertNotNull(annotationType);
    assertEquals(name, annotationType.getTypeName());
    assertEquals(Annotation.ANNOTATION_SUPER_TYPE_NAME, annotationType.getSupertype());
    assertEquals(1, annotationType.getFields().size());
    assertNotNull(annotationType.getField(fieldName));
  }

  @Test
  void registerAnnotationTypeNew() {
    String name = "custom.Annotation";
    Type annotationType = recording.registerAnnotationType(name);
    assertNotNull(annotationType);
    assertEquals(name, annotationType.getTypeName());
    assertEquals(Annotation.ANNOTATION_SUPER_TYPE_NAME, annotationType.getSupertype());
  }

  @Test
  void registerAnnotationTypeExisting() {
    String name = "custom.Annotation";
    Type annotationType = recording.registerAnnotationType(name);

    Type annotationType1 = recording.registerAnnotationType(name);

    assertEquals(annotationType, annotationType1);
  }

  @Test
  void registerTypeNulls() {
    assertThrows(IllegalArgumentException.class, () -> recording.registerType(null, builder -> {}));
    assertThrows(IllegalArgumentException.class, () -> recording.registerType("name", null));
    assertThrows(IllegalArgumentException.class, () -> recording.registerType(null, null));
    assertThrows(
        IllegalArgumentException.class, () -> recording.registerType(null, "super", builder -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> recording.registerType("name", "super", null));
    assertThrows(IllegalArgumentException.class, () -> recording.registerType(null, "super", null));
    assertThrows(
        IllegalArgumentException.class, () -> recording.registerType(null, null, builder -> {}));
    assertThrows(IllegalArgumentException.class, () -> recording.registerType("name", null, null));
    assertThrows(IllegalArgumentException.class, () -> recording.registerType(null, null, null));
  }

  @ParameterizedTest
  @EnumSource(Types.JDK.class)
  void getBuiltinJDKType(Types.JDK target) {
    Type type = recording.getType(target);
    assertNotNull(type);
  }

  @Test
  void getNullType() {
    assertThrows(IllegalArgumentException.class, () -> recording.getType((Types.JDK) null));
    assertThrows(IllegalArgumentException.class, () -> recording.getType((String) null));
  }

  @Test
  void getInvalidType() {
    assertThrows(IllegalArgumentException.class, () -> recording.getType("Invalid type"));
  }

  @Test
  void getRegisteredType() {
    String typeName = "custom.Type";
    Type type = recording.registerType(typeName, builder -> {});

    Type type1 = recording.getType(typeName);
    assertNotNull(type1);
    assertEquals(type, type1);
  }
}
