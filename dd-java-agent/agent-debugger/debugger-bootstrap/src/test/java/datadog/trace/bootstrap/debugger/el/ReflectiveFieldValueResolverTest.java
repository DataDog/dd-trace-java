package datadog.trace.bootstrap.debugger.el;

import static datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver.getFieldAsCapturedValue;
import static datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver.resolve;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.debugger.CapturedContext;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

class ReflectiveFieldValueResolverTest {

  @Test
  void testGetFieldAsCapturedValue() {
    assertNull(getFieldAsCapturedValue(null, "fieldName").getValue());
    C c = new C();
    assertEquals(3, getFieldAsCapturedValue(c, "c1").getValue());
    assertEquals(1, getFieldAsCapturedValue(c, "a1").getValue());
    assertEquals("2", getFieldAsCapturedValue(c, "a2").getValue());
    assertNull(getFieldAsCapturedValue(c, "notExist").getValue());
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  void testGetFieldAsCapturedValueInaccessible() {
    CapturedContext.CapturedValue elementData =
        getFieldAsCapturedValue(new ArrayList<>(), "elementData");
    assertEquals(
        "Field is not accessible: module java.base does not opens/exports to the current module",
        elementData.getNotCapturedReason());
  }

  @Test
  void testResolve() {
    assertEquals(1, resolve(new A(), A.class, "a1"));
    assertEquals("2", resolve(new A(), A.class, "a2"));
    assertEquals(3, resolve(new C(), C.class, "c1"));
    assertEquals(1, resolve(new C(), C.class, "a1"));
    assertEquals(4, resolve(new C(), C.class, "c2"));
    assertEquals(Values.UNDEFINED_OBJECT, resolve(new C(), C.class, "unknown"));
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  void testResolveInaccessible() {
    assertEquals(
        Values.UNDEFINED_OBJECT, resolve(new ArrayList<>(), ArrayList.class, "elementData"));
  }

  static class A {
    private int a1 = 1;
    private String a2 = "2";
  }

  static class B extends A {}

  static class C extends A {
    private int c1 = 3;
    private static int c2 = 4;
  }
}
