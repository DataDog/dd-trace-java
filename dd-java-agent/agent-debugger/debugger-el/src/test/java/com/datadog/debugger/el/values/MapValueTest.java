package com.datadog.debugger.el.values;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapValueTest {
  private MapValue instance;

  @BeforeEach
  void setup() throws Exception {
    Map<String, String> map = new HashMap<>();
    map.put("a", "a");
    map.put("b", null);
    instance = new MapValue(map);
  }

  @Test
  void isEmpty() {
    assertFalse(instance.isEmpty());
  }

  @Test
  void count() {
    assertEquals(2, instance.count());
  }

  @Test
  void get() {
    assertEquals(Value.of("a"), instance.get("a"));
    assertEquals(Value.of("a"), instance.get(Value.of("a")));
    assertEquals(Value.nullValue(), instance.get("b"));
    assertEquals(Value.nullValue(), instance.get(Value.of("b")));
    assertEquals(Value.undefinedValue(), instance.get("c"));
    assertEquals(Value.undefinedValue(), instance.get(Value.of("c")));
    assertEquals(Value.undefinedValue(), instance.get(Values.UNDEFINED_OBJECT));
    assertEquals(Value.undefinedValue(), instance.get(Value.undefinedValue()));
  }
}
