package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnnotationTest {
  private Types types;

  @BeforeEach
  void setup() {
    ConstantPools constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);
  }

  @Test
  void validAnnotationTypeNullValue() {
    Type type = types.getType(Types.JDK.ANNOTATION_LABEL);
    Annotation annotation = new Annotation(type, null);
    assertNotNull(annotation);
    assertNull(annotation.getValue());
    assertEquals(type, annotation.getType());
  }

  @Test
  void validAnnotationTypeWithValue() {
    String value = "value";
    Type type = types.getType(Types.JDK.ANNOTATION_LABEL);
    Annotation annotation = new Annotation(type, value);
    assertNotNull(annotation);
    assertEquals(value, annotation.getValue());
    assertEquals(type, annotation.getType());
  }

  @Test
  void invalidAnnotationType() {
    Type type = types.getType(Types.Builtin.STRING);
    assertThrows(IllegalArgumentException.class, () -> new Annotation(type, null));
  }
}
