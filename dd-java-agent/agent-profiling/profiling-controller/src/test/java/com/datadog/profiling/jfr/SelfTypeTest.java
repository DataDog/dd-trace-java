package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SelfTypeTest {
  @Test
  void isBuiltin() {
    assertFalse(SelfType.INSTANCE.isBuiltin());
  }

  @Test
  void getFields() {
    assertNull(SelfType.INSTANCE.getFields());
  }

  @Test
  void getField() {
    assertNull(SelfType.INSTANCE.getField("field"));
  }

  @Test
  void getAnnotations() {
    assertNull(SelfType.INSTANCE.getAnnotations());
  }

  @Test
  void canAccept() {
    assertTrue(SelfType.INSTANCE.canAccept(null));
    assertFalse(SelfType.INSTANCE.canAccept("value"));
  }
}
