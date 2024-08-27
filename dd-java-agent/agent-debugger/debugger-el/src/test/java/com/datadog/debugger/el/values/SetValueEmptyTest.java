package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SetValueEmptyTest {
  private SetValue instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new SetValue(Collections.emptySet());
  }

  @Test
  void prettyPrint() {
    assertEquals("Set", print(instance));
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
    assertEquals(BooleanValue.FALSE, instance.get("a"));
    assertEquals(BooleanValue.FALSE, instance.get("b"));
    assertEquals(Value.undefinedValue(), instance.get(Values.UNDEFINED_OBJECT));
    assertEquals(Value.undefinedValue(), instance.get(Value.undefinedValue()));
    assertEquals(Value.nullValue(), instance.get(Values.NULL_OBJECT));
    assertEquals(Value.nullValue(), instance.get(Value.nullValue()));
    assertEquals(Value.nullValue(), instance.get(null));
  }
}
