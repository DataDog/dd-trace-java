package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapValueUndefinedTest {
  private MapValue instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new MapValue("a");
  }

  @Test
  void isEmpty() {
    assertTrue(instance.isEmpty());
  }

  @Test
  void count() {
    assertEquals(-1, instance.count());
  }

  @Test
  void get() {
    assertEquals(Value.undefinedValue(), instance.get(0));
    assertEquals(Value.undefinedValue(), instance.get(10));
  }
}
