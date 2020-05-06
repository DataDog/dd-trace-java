package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TypeValueBuilderImplTest {
  private static final String CUSTOM_FIELD_NAME = "custom_field";
  private static final String CUSTOM_FIELD_ARRAY_NAME = "custom_field_arr";
  private static final String SIMPLE_FIELD_VALUE = "hello";
  private static final String SIMPLE_FIELD_NAME = "field";
  private static Map<Types.Builtin, String> typeToFieldMap;
  private TypeValueBuilderImpl instance;
  private JFRType simpleType;
  private JFRType customType;

  @BeforeAll
  static void init() {
    typeToFieldMap = new HashMap<>(Types.Builtin.values().length);
    for (Types.Builtin builtin : Types.Builtin.values()) {
      typeToFieldMap.put(builtin, builtin.name().toLowerCase() + "_field");
    }
  }

  @BeforeEach
  void setUp() {
    // not mocking here since we will need quite a number of predefined types anyway
    Types types = new Types(new Metadata(new ConstantPools()));

    simpleType =
        types.getOrAdd(
            "custom.Simple",
            builder -> {
              builder.addField(SIMPLE_FIELD_NAME, Types.Builtin.STRING);
            });

    customType =
        types.getOrAdd(
            "custom.Type",
            builder -> {
              for (Types.Builtin builtin : Types.Builtin.values()) {
                builder
                    .addField(getFieldName(builtin), builtin)
                    .addField(getArrayFieldName(builtin), builtin, TypedFieldBuilder::asArray);
              }
              builder
                  .addField(CUSTOM_FIELD_NAME, simpleType)
                  .addField(CUSTOM_FIELD_ARRAY_NAME, simpleType, TypedFieldBuilder::asArray);
            });

    instance = new TypeValueBuilderImpl(customType);
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void putFieldBuiltin(Types.Builtin target) {
    for (Types.Builtin type : Types.Builtin.values()) {
      if (type == target) {
        assertCorrectFieldValueBuiltinType(target, type, 1, false);
      } else {
        assertWrongFieldValueBuiltinType(target, type, 0, false);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void putFieldBuiltinArray(Types.Builtin target) {
    for (Types.Builtin type : Types.Builtin.values()) {
      if (type == target) {
        assertCorrectFieldValueBuiltinType(target, type, 1, true);
      } else {
        assertWrongFieldValueBuiltinType(target, type, 0, true);
      }
    }
  }

  @Test
  void putFieldCustom() {
    instance.putField(CUSTOM_FIELD_NAME, SIMPLE_FIELD_VALUE);

    TypedFieldValue fieldValue = instance.build().get(CUSTOM_FIELD_NAME);
    assertNotNull(fieldValue);
    assertEquals(CUSTOM_FIELD_NAME, fieldValue.getField().getName());
    assertEquals(SIMPLE_FIELD_VALUE, fieldValue.getValue().getValue());
  }

  @Test
  void putFieldCustomInvalid() {
    assertThrows(IllegalArgumentException.class, () -> instance.putField(CUSTOM_FIELD_NAME, 0L));
  }

  @Test
  public void putFieldCustomArray() {
    TypedFieldValue value =
        instance
            .putField(
                CUSTOM_FIELD_ARRAY_NAME,
                fld1 -> {
                  fld1.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                },
                fld2 -> {
                  fld2.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                })
            .build()
            .get(CUSTOM_FIELD_ARRAY_NAME);

    assertNotNull(value);
  }

  private void assertCorrectFieldValueBuiltinType(
      Types.Builtin target, Types.Builtin type, int value, boolean asArray) {
    if (asArray) {
      testPutBuiltinFieldArray(target, type, value);
    } else {
      testPutBuiltinField(target, type, value);
    }

    String fieldName = asArray ? getArrayFieldName(type) : getFieldName(type);
    TypedFieldValue fieldValue = instance.build().get(fieldName);
    assertNotNull(fieldValue);
    assertEquals(fieldName, fieldValue.getField().getName());

    Object targetValue = null;
    if (asArray) {
      Object targetValues = fieldValue.getValues();
      assertNotNull(targetValues);
      assertTrue(targetValues.getClass().isArray());
      targetValue = Array.get(targetValues, 0);
    } else {
      targetValue = fieldValue.getValue().getValue();
    }
    assertNotNull(targetValue);
    if (targetValue instanceof Number) {
      assertEquals(value, ((Number) targetValue).intValue());
    } else if (targetValue instanceof String) {
      assertEquals(String.valueOf(value), targetValue);
    } else if (targetValue instanceof Boolean) {
      assertEquals(value > 0, targetValue);
    }
  }

  private void assertWrongFieldValueBuiltinType(
      Types.Builtin target, Types.Builtin type, int value, boolean asArray) {
    assertThrows(IllegalArgumentException.class, () -> testPutBuiltinField(target, type, value));
  }

  private void testPutBuiltinField(Types.Builtin target, Types.Builtin type, int value) {
    switch (target) {
      case BYTE:
        {
          instance.putField(getFieldName(type), (byte) value);
          break;
        }
      case CHAR:
        {
          instance.putField(getFieldName(type), (char) value);
          break;
        }
      case SHORT:
        {
          instance.putField(getFieldName(type), (short) value);
          break;
        }
      case INT:
        {
          instance.putField(getFieldName(type), (int) value);
          break;
        }
      case LONG:
        {
          instance.putField(getFieldName(type), (long) value);
          break;
        }
      case FLOAT:
        {
          instance.putField(getFieldName(type), (float) value);
          break;
        }
      case DOUBLE:
        {
          instance.putField(getFieldName(type), (double) value);
          break;
        }
      case BOOLEAN:
        {
          instance.putField(getFieldName(type), (int) (value) > 0);
          break;
        }
      case STRING:
        {
          instance.putField(getFieldName(type), String.valueOf(value));
          break;
        }
    }
  }

  private void testPutBuiltinFieldArray(Types.Builtin target, Types.Builtin type, int value) {
    switch (target) {
      case BYTE:
        {
          instance.putField(getArrayFieldName(type), new byte[] {(byte) value});
          break;
        }
      case CHAR:
        {
          instance.putField(getArrayFieldName(type), new char[] {(char) value});
          break;
        }
      case SHORT:
        {
          instance.putField(getArrayFieldName(type), new short[] {(short) value});
          break;
        }
      case INT:
        {
          instance.putField(getArrayFieldName(type), new int[] {(int) value});
          break;
        }
      case LONG:
        {
          instance.putField(getArrayFieldName(type), new long[] {(long) value});
          break;
        }
      case FLOAT:
        {
          instance.putField(getArrayFieldName(type), new float[] {(float) value});
          break;
        }
      case DOUBLE:
        {
          instance.putField(getArrayFieldName(type), new double[] {(double) value});
          break;
        }
      case BOOLEAN:
        {
          instance.putField(getArrayFieldName(type), new boolean[] {(int) (value) > 0});
          break;
        }
      case STRING:
        {
          instance.putField(getArrayFieldName(type), new String[] {String.valueOf(value)});
          break;
        }
    }
  }

  private static String getFieldName(Types.Builtin type) {
    return typeToFieldMap.get(type);
  }

  private static String getArrayFieldName(Types.Builtin type) {
    return getFieldName(type) + "_arr";
  }
}
