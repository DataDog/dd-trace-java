package com.datadog.debugger.el.values;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SetValueNullTest {
  private SetValue instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new SetValue(null);
  }

  @Test
  void prettyPrint() {
    assertEquals("null", print(instance));
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
