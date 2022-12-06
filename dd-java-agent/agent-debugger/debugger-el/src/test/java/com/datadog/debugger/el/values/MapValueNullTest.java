package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapValueNullTest {
  private MapValue instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new MapValue(null);
  }

  @Test
  void isEmpty() {
    assertTrue(instance.isEmpty());
  }

  @Test
  void count() {
    assertEquals(0, instance.count());
  }

  @Test
  void get() {
    assertEquals(Value.nullValue(), instance.get(0));
    assertEquals(Value.nullValue(), instance.get(10));
  }
}
