package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConstantPoolsTest {
  private ConstantPools instance;

  @BeforeEach
  void setUp() {
    instance = new ConstantPools();
  }

  @Test
  void forTypeWithCP() {
    JFRType type = Mockito.mock(JFRType.class);
    Mockito.when(type.hasConstantPool()).thenReturn(true);
    assertNotNull(instance.forType(type));
  }

  @Test
  void forTypeWithoutCP() {
    JFRType type = Mockito.mock(JFRType.class);
    Mockito.when(type.hasConstantPool()).thenReturn(false);
    assertThrows(IllegalArgumentException.class, () -> instance.forType(type));
  }

  @Test
  void size() {
    assertEquals(0, instance.size());
    JFRType type = Mockito.mock(JFRType.class);
    Mockito.when(type.hasConstantPool()).thenReturn(true);
    instance.forType(type);
    assertEquals(1, instance.size());
  }
}
