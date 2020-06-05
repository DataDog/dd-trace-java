package com.datadog.mlt.io;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConstantPoolTest {
  private ConstantPool<String> instance;

  @BeforeEach
  void setUp() {
    instance = new ConstantPool<>();
  }

  @Test
  void getByIndexEmpty() {
    assertNull(instance.get(-1));
    assertNull(instance.get(0));
  }

  @Test
  void getByIndex() {
    String value = "test";
    instance.insert(0, value);
    assertNull(instance.get(-1));
    assertEquals(value, instance.get(0));
  }

  @Test
  void getByValueFromEmpty() {
    String value = "test";
    int ptr = instance.getOrInsert(value);
    assertNotEquals(-1, ptr);
    assertEquals(value, instance.get(ptr));
  }

  @Test
  void getByValueFromPrefilled() {
    int ptr = 0;
    String value = "test";
    instance.insert(0, value);
    assertEquals(value, instance.get(ptr));
    assertEquals(ptr, instance.getOrInsert(value));
  }

  @Test
  void insertInvalid() {
    assertThrows(IllegalArgumentException.class, () -> instance.insert(1, null));
  }

  @Test
  void insertNull() {
    assertDoesNotThrow(() -> instance.insert(-1, null));
  }

  @Test
  void size() {
    String value1 = "test1";
    String value2 = "test2";
    assertEquals(0, instance.size());
    instance.insert(0, value1);
    assertEquals(1, instance.size());
    instance.getOrInsert(value1);
    assertEquals(1, instance.size());
    instance.getOrInsert(value2);
    assertEquals(2, instance.size());
  }
}
