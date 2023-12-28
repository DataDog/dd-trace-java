package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapValueEmptyTest {
  private MapValue instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new MapValue(Collections.emptyMap());
  }

  @Test
  void prettyPrint() {
    assertEquals("Map", print(instance));
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
    assertEquals(Value.undefinedValue(), instance.get("a", null));
    assertEquals(Value.undefinedValue(), instance.get("b", null));
    assertEquals(Value.undefinedValue(), instance.get(Values.UNDEFINED_OBJECT, null));
    assertEquals(Value.undefinedValue(), instance.get(Value.undefinedValue(), null));
    assertEquals(Value.nullValue(), instance.get(Values.NULL_OBJECT, null));
    assertEquals(Value.nullValue(), instance.get(Value.nullValue(), null));
    assertEquals(Value.nullValue(), instance.get(null, null));
  }
}
