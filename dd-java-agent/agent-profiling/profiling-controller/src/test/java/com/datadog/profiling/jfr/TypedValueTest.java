package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TypedValueTest {
  private ConstantPools constantPools;
  private Metadata metadata;
  private Types types;

  @BeforeEach
  void setup() {
    constantPools = new ConstantPools();
    metadata = new Metadata(constantPools);
    types = new Types(metadata);
  }

  @Test
  void invalidValue() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TypedValue.of(types.getType(Types.Builtin.STRING), 1));
  }

  @Test
  void ofBuiltinNonCp() {
    Type type = types.getType(Types.Builtin.INT);
    TypedValue value = TypedValue.of(type, 1);
    assertNotNull(value);
    assertEquals(type, value.getType());
    assertEquals(1, value.getValue());
    assertEquals(Long.MIN_VALUE, value.getCPIndex());
  }

  @Test
  void ofBuiltinCp() {
    Type type = types.getType(Types.Builtin.STRING);
    String targetValue = "hello";
    TypedValue value = TypedValue.of(type, targetValue);
    assertNotNull(value);
    assertFalse(value.isNull());
    assertEquals(type, value.getType());
    assertEquals(targetValue, value.getValue());
    assertNotEquals(Long.MIN_VALUE, value.getCPIndex());
  }

  @Test
  void ofCustom() {
    String targetValue = "hello";
    Type type =
        types.getOrAdd(
            "type.Custom",
            t -> {
              t.addField("field", types.getType(Types.Builtin.STRING));
            });

    TypedValue value =
        TypedValue.of(
            type,
            v -> {
              v.putField("field", targetValue);
            });
    assertNotNull(value);
    assertFalse(value.isNull());
    assertEquals(type, value.getType());
    assertEquals(targetValue, value.getValue());
    assertNotEquals(Long.MIN_VALUE, value.getCPIndex());
  }

  @Test
  void ofCustomNoCP() {
    TypeStructure structure =
        new TypeStructure(
            Collections.singletonList(new TypedField(types.getType(Types.Builtin.STRING), "field")),
            Collections.emptyList());
    CompositeType nonCpType = new CompositeType(1234, "test.Type", null, structure, null, metadata);

    TypedValue typedValue =
        TypedValue.of(
            nonCpType,
            t -> {
              t.putField("field", "Ho!");
            });
    assertNotNull(typedValue);
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void ofNull(Types.Builtin type) {
    TypedValue nullValue = TypedValue.ofNull(types.getType(type));
    assertNotNull(nullValue);
    assertTrue(nullValue.isNull());
    assertThrows(NullPointerException.class, nullValue::getFieldValues);
  }

  @Test
  void ofNullInvalid() {
    TestType type1 =
        new TestType(1234, "test.Type", null, constantPools, metadata) {
          @Override
          public boolean canAccept(Object value) {
            // disallow null values
            return value != null;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> TypedValue.ofNull(type1));
  }

  @Test
  void ofNullCustom() {
    Type type =
        types.getOrAdd(
            "type.Custom",
            t -> {
              t.addField("field", types.getType(Types.Builtin.STRING));
            });
    assertNotNull(TypedValue.ofNull(type));
  }

  @Test
  void copyBuiltinWithCp() {
    int newCpIndex = 10;
    Type type = types.getType(Types.Builtin.STRING);
    String targetValue = "hello";
    TypedValue value = new TypedValue(type, targetValue, -1);

    assertEquals(-1, value.getCPIndex());
    assertThrows(IllegalArgumentException.class, () -> TypedValue.withCpIndex(value, newCpIndex));
  }

  @Test
  void copyCustomWithCp() {
    int newCpIndex = 10;
    String targetValue = "hello";

    Type type =
        types.getOrAdd(
            "type.Custom",
            t -> {
              t.addField("field", types.getType(Types.Builtin.STRING));
            });

    TypedValue value =
        new TypedValue(
            type,
            Collections.singletonMap(
                "field",
                new TypedFieldValue(
                    type.getField("field"), type.getField("field").getType().asValue(targetValue))),
            -1);

    assertEquals(-1, value.getCPIndex());
    assertEquals(newCpIndex, TypedValue.withCpIndex(value, newCpIndex).getCPIndex());
  }

  @Test
  void getFieldValues() {
    Type type =
        types.getOrAdd(
            "type.Custom",
            t -> {
              t.addField("field1", types.getType(Types.Builtin.STRING))
                  .addField("field2", types.getType(Types.Builtin.STRING));
            });

    TypedValue typedValue =
        TypedValue.of(
            type,
            v -> {
              v.putField("field1", "value1");
            });

    List<TypedFieldValue> fields = typedValue.getFieldValues();
    assertEquals(2, fields.size());
    for (TypedFieldValue tValue : fields) {
      assertNotNull(tValue);
      if (tValue.getValue().isNull()) {
        assertNull(tValue.getValue().getValue());
      } else {
        assertEquals("value1", tValue.getValue().getValue());
      }
    }
  }

  @Test
  void testEquality() {
    Type type1 = types.getType(Types.Builtin.STRING);
    Type type2 =
        types.getOrAdd(
            "type.Custom",
            t -> {
              t.addField("field1", types.getType(Types.Builtin.STRING))
                  .addField("field2", types.getType(Types.Builtin.STRING));
            });

    TypedValue value1_1 = type1.asValue("hello");
    TypedValue value1_2 = type1.asValue("world");
    TypedValue value2_1 =
        type2.asValue(
            v -> {
              v.putField("field1", "hello");
            });
    TypedValue value2_2 =
        type2.asValue(
            v -> {
              v.putField("field2", "world");
            });

    assertEquals(value1_1, value1_1);
    assertEquals(value1_2, value1_2);
    assertEquals(value2_1, value2_1);
    assertEquals(value2_2, value2_2);

    assertNotEquals(null, value1_1);
    assertNotEquals(null, value1_2);
    assertNotEquals(null, value2_1);
    assertNotEquals(null, value2_2);

    assertNotEquals(value1_1, null);
    assertNotEquals(value1_2, null);
    assertNotEquals(value2_1, null);
    assertNotEquals(value2_2, null);

    assertNotEquals(1, value1_1);
    assertNotEquals(1, value1_2);
    assertNotEquals(1, value2_1);
    assertNotEquals(1, value2_2);

    assertNotEquals(value1_1, 1);
    assertNotEquals(value1_2, 1);
    assertNotEquals(value2_1, 1);
    assertNotEquals(value2_2, 1);

    assertNotEquals(value1_1, value1_2);
    assertNotEquals(value1_1, value2_1);
    assertNotEquals(value1_1, value2_2);
    assertNotEquals(value1_2, value1_1);
    assertNotEquals(value1_2, value2_1);
    assertNotEquals(value1_2, value2_2);
    assertNotEquals(value2_1, value1_1);
    assertNotEquals(value2_1, value1_2);
    assertNotEquals(value2_1, value2_2);
    assertNotEquals(value2_2, value1_1);
    assertNotEquals(value2_2, value1_2);
    assertNotEquals(value2_2, value2_1);
  }
}
