package datadog.trace.bootstrap.debugger.el;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReflectiveFieldValueResolverTest {

  @Test
  void getFieldAsCapturedValue() {
    assertNull(ReflectiveFieldValueResolver.getFieldAsCapturedValue(null, "fieldName").getValue());
    C c = new C();
    assertEquals(3, ReflectiveFieldValueResolver.getFieldAsCapturedValue(c, "c1").getValue());
    assertEquals(1, ReflectiveFieldValueResolver.getFieldAsCapturedValue(c, "a1").getValue());
    assertEquals("2", ReflectiveFieldValueResolver.getFieldAsCapturedValue(c, "a2").getValue());
    assertNull(ReflectiveFieldValueResolver.getFieldAsCapturedValue(c, "notExist").getValue());
  }

  static class A {
    private int a1 = 1;
    private String a2 = "2";
  }

  static class B extends A {}

  static class C extends A {
    private int c1 = 3;
  }
}
