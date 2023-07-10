package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.ensureSafeClassLoad;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class ASMHelperTest {

  @Test
  public void ensureSafeLoadClass() {
    IllegalArgumentException illegalArgumentException =
        assertThrows(IllegalArgumentException.class, () -> ensureSafeClassLoad("", null, null));
    assertEquals(
        "Cannot ensure loading class:  safely as current class being transformed is not provided (null)",
        illegalArgumentException.getMessage());
    illegalArgumentException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ensureSafeClassLoad(
                    "com.datadog.debugger.MyClass", "com.datadog.debugger.MyClass", null));
    assertEquals(
        "Cannot load class com.datadog.debugger.MyClass as this is the class being currently transformed",
        illegalArgumentException.getMessage());
    Class<?> clazz =
        ensureSafeClassLoad(
            ASMHelperTest.class.getTypeName(), "", ASMHelperTest.class.getClassLoader());
    assertEquals(ASMHelperTest.class, clazz);
  }
}
