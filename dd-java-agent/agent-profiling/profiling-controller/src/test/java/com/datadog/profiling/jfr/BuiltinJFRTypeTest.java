package com.datadog.profiling.jfr;

import static com.datadog.profiling.jfr.TypeUtils.BUILTIN_VALUE_MAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

class BuiltinJFRTypeTest {
  private BuiltinJFRType instance;

  @BeforeEach
  void setUp() {
    instance =
        new BuiltinJFRType(
            1,
            Types.Builtin.STRING,
            Mockito.mock(ConstantPools.class),
            Mockito.mock(Metadata.class));
  }

  @Test
  void isBuiltin() {
    assertTrue(instance.isBuiltin());
  }

  @Test
  void isResolved() {
    assertTrue(instance.isResolved());
  }

  @Test
  void nullValue() {
    TypedValue value = instance.nullValue();
    assertNotNull(value);
    assertTrue(value.isNull());
  }

  @Test
  void getFields() {
    assertTrue(instance.getFields().isEmpty());
  }

  @Test
  void getField() {
    assertThrows(IllegalArgumentException.class, () -> instance.getField("field"));
  }

  @Test
  void getAnnotations() {
    assertTrue(instance.getAnnotations().isEmpty());
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void canAccept(Types.Builtin target) {
    BaseJFRType type =
        new BuiltinJFRType(
            1, target, Mockito.mock(ConstantPools.class), Mockito.mock(Metadata.class));
    for (Types.Builtin builtin : Types.Builtin.values()) {
      assertEquals(target == builtin, type.canAccept(BUILTIN_VALUE_MAP.get(builtin)));
    }
  }
}
