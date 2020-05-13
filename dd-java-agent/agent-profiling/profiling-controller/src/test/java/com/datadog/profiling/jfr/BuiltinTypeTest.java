package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class BuiltinTypeTest {
  private static final long TYPE_ID = 1L;
  private BuiltinType instance;

  @BeforeEach
  void setUp() {
    instance =
        new BuiltinType(
            TYPE_ID,
            Types.Builtin.INT,
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
    assertEquals(value, instance.nullValue());
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

  @Test
  void getId() {
    assertEquals(TYPE_ID, instance.getId());
  }

  @Test
  void getTypeName() {
    assertEquals(Types.Builtin.INT.getTypeName(), instance.getTypeName());
  }

  @Test
  void getSupertype() {
    assertNull(instance.getSupertype());
  }

  @Test
  void getMetadata() {
    assertNotNull(instance.getMetadata());
  }

  @Test
  void getConstantPool() {
    assertNull(instance.getConstantPool());
  }

  @Test
  void isUsedBy() {
    Type other =
        new BuiltinType(
            TYPE_ID,
            Types.Builtin.STRING,
            Mockito.mock(ConstantPools.class),
            Mockito.mock(Metadata.class));

    assertFalse(instance.isUsedBy(null));
    assertFalse(instance.isUsedBy(instance));
    assertFalse(instance.isUsedBy(other));
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void canAccept(Types.Builtin target) {
    BaseType type =
        new BuiltinType(1, target, Mockito.mock(ConstantPools.class), Mockito.mock(Metadata.class));
    for (Types.Builtin builtin : Types.Builtin.values()) {
      for (Object value : TypeUtils.getBuiltinValues(builtin)) {
        assertEquals(
            target == builtin || value == null,
            type.canAccept(value)); // null is generally accepted
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void equality(Types.Builtin target) {
    BaseType type1 =
        new BuiltinType(1, target, Mockito.mock(ConstantPools.class), Mockito.mock(Metadata.class));
    for (Types.Builtin builtin : Types.Builtin.values()) {
      BaseType type2 =
          new BuiltinType(
              1, builtin, Mockito.mock(ConstantPools.class), Mockito.mock(Metadata.class));

      assertFalse(type1.equals(null));
      assertTrue(type1.equals(type1));
      assertEquals(target == builtin, type1.equals(type2));
      assertEquals(target == builtin, type2.equals(type1));
    }
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void asValue(Types.Builtin target) throws Exception {
    // STRING builtin needs a constant pool; other builtins must not have a constant pool
    ConstantPools constantPools = Mockito.mock(ConstantPools.class);
    Mockito.when(constantPools.forType(ArgumentMatchers.any(Type.class)))
        .thenAnswer(i -> new ConstantPool(i.getArgument(0)));

    BaseType type =
        new BuiltinType(
            1,
            target,
            target == Types.Builtin.STRING ? constantPools : null,
            Mockito.mock(Metadata.class));
    for (Types.Builtin builtin : Types.Builtin.values()) {
      for (Object fromValue : TypeUtils.getBuiltinValues(builtin)) {
        Method asValueMethod = getAsValueMethod(builtin);

        if (target == builtin) {
          TypedValue typedValue = (TypedValue) asValueMethod.invoke(type, fromValue);

          assertNotNull(typedValue);
          assertEquals(fromValue, typedValue.getValue());
        } else {
          try {
            asValueMethod.invoke(type, fromValue);
            // for 'null' values there is no extra type info so really can't assert anything
            if (fromValue != null) {
              fail(
                  "Attempted conversion of a value of type '"
                      + builtin.getTypeClass()
                      + "' to '"
                      + target.getTypeClass()
                      + "'");
            }
          } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalArgumentException)) {
              fail(e);
            }
          }
        }
      }
    }

    assertThrows(
        IllegalArgumentException.class,
        () ->
            type.asValue(
                v -> {
                  v.putField("f1", "no value");
                }));
  }

  private static Method getAsValueMethod(Types.Builtin type) throws Exception {
    return BaseType.class.getMethod("asValue", type.getTypeClass());
  }
}
