package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

class SpringHelperTest {

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  void isSpringUsingOnlyMethodParametersTrue() throws Exception {
    Class<?> clazz =
        Class.forName(
            "org.springframework.web.bind.annotation.ControllerMappingReflectiveProcessor");
    Instrumentation inst = mock(Instrumentation.class);
    when(inst.getAllLoadedClasses()).thenReturn(new Class[] {clazz});
    assertTrue(SpringHelper.isSpringUsingOnlyMethodParameters(inst));
  }

  @Test
  void isSpringUsingOnlyMethodParametersFalse() throws Exception {
    Instrumentation inst = mock(Instrumentation.class);
    when(inst.getAllLoadedClasses()).thenReturn(new Class[0]);
    assertFalse(SpringHelper.isSpringUsingOnlyMethodParameters(inst));
  }
}
