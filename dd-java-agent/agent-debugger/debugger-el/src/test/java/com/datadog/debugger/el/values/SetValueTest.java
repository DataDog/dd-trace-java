package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetValueTest {
  private SetValue instance;

  @BeforeEach
  void setup() throws Exception {
    Set<String> set = new HashSet<>();
    set.add("foo");
    set.add("bar");
    instance = new SetValue(set);
  }

  @Test
  void prettyPrint() {
    assertEquals("Set", print(instance));
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
    assertEquals(Value.of(true, ValueType.BOOLEAN), instance.get("foo"));
    assertEquals(BooleanValue.TRUE, instance.get("foo"));
    assertEquals(
        Value.of(true, ValueType.BOOLEAN), instance.get(Value.of("foo", ValueType.OBJECT)));
    assertEquals(Value.of(false, ValueType.BOOLEAN), instance.get("oof"));
    assertEquals(BooleanValue.FALSE, instance.get("oof"));
    assertEquals(
        Value.of(false, ValueType.BOOLEAN), instance.get(Value.of("oof", ValueType.OBJECT)));
    assertEquals(Value.undefinedValue(), instance.get(Values.UNDEFINED_OBJECT));
    assertEquals(Value.undefinedValue(), instance.get(Value.undefinedValue()));
  }

  @Test
  void nullSet() {
    SetValue setValue = new SetValue(null);
    assertTrue(setValue.isEmpty());
    assertTrue(setValue.isNull());
    setValue = new SetValue(Values.NULL_OBJECT);
    assertTrue(setValue.isEmpty());
    assertTrue(setValue.isNull());
  }
}
