package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
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
  void prettyPrint() {
    assertEquals("Map", print(instance));
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
    assertEquals(Value.of("a", ValueType.OBJECT), instance.get("a"));
    assertEquals(Value.of("a", ValueType.OBJECT), instance.get(Value.of("a", ValueType.OBJECT)));
    assertEquals(Value.nullValue(), instance.get("b"));
    assertEquals(Value.nullValue(), instance.get(Value.of("b", ValueType.OBJECT)));
    assertEquals(Value.nullValue(), instance.get("c"));
    assertEquals(Value.nullValue(), instance.get(Value.of("c", ValueType.OBJECT)));
    assertEquals(Value.undefinedValue(), instance.get(Values.UNDEFINED_OBJECT));
    assertEquals(Value.undefinedValue(), instance.get(Value.undefinedValue()));
  }

  @Test
  void intMap() {
    Map<Integer, Integer> map = new HashMap<>();
    map.put(1, 1);
    map.put(2, 2);
    instance = new MapValue(map);
    assertEquals(2, instance.count());
    assertEquals(Value.of(1, ValueType.INT), instance.get(1));
    assertEquals(Value.of(2, ValueType.INT), instance.get(2));
    Value<?> key = Value.of(1, ValueType.INT);
    assertEquals(Value.of(1, ValueType.INT), instance.get(key));
  }

  @Test
  void nullMap() {
    MapValue mapValue = new MapValue(null);
    assertTrue(mapValue.isEmpty());
    assertTrue(mapValue.isNull());
    mapValue = new MapValue(Values.NULL_OBJECT);
    assertTrue(mapValue.isEmpty());
    assertTrue(mapValue.isNull());
  }
}
