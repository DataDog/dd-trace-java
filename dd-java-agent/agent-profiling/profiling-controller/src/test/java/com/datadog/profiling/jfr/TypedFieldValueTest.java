package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypedFieldValueTest {
  private Types types;

  @BeforeEach
  void setup() {
    types = new Types(new Metadata(new ConstantPools()));
  }

  @Test
  void testArrayForScalarField() {
    Type type = types.getType(Types.Builtin.STRING);
    TypedField field = new TypedField(type, "field");
    TypedValue value = type.asValue("hello");

    assertThrows(
        IllegalArgumentException.class,
        () -> new TypedFieldValue(field, new TypedValue[] {value, value}));
  }

  @Test
  void testScalarValue() {
    Type type = types.getType(Types.Builtin.STRING);
    TypedField field = new TypedField(type, "field");
    TypedValue value = type.asValue("hello");

    TypedFieldValue instance = new TypedFieldValue(field, value);

    assertNotNull(instance.getValue());
    assertEquals(field, instance.getField());
    assertEquals(value, instance.getValue());
    assertThrows(IllegalArgumentException.class, instance::getValues);
  }

  @Test
  void testArrayValue() {
    Type type = types.getType(Types.Builtin.STRING);
    TypedField field = new TypedField(type, "field", true);
    TypedValue value1 = type.asValue("hello");
    TypedValue value2 = type.asValue("world");

    TypedFieldValue instance = new TypedFieldValue(field, new TypedValue[] {value1, value2});

    assertNotNull(instance.getValues());
    assertEquals(field, instance.getField());
    assertArrayEquals(new TypedValue[] {value1, value2}, instance.getValues());
    assertThrows(IllegalArgumentException.class, instance::getValue);
  }
}
