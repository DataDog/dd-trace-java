package com.datadog.profiling.jfr;

import static com.datadog.profiling.jfr.TypeUtils.BUILTIN_VALUE_MAP;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomJFRTypeTest {
  private static final String TYPE_NAME = "test.Type";
  private static final String FIELD_NAME = "field1";
  private static final String PARENT_FIELD_NAME = "parent";
  private static final String FIELD_VALUE = "hello";
  private CustomJFRType instance;
  private Types types;

  @BeforeEach
  void setUp() {
    ConstantPools constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);

    List<TypedField> fields = new ArrayList<>();
    List<JFRAnnotation> annotations = new ArrayList<>();

    fields.add(new TypedField(types.getType(Types.Builtin.STRING), FIELD_NAME));
    fields.add(new TypedField(CustomJFRType.SELF_TYPE, PARENT_FIELD_NAME));
    annotations.add(new JFRAnnotation(types.getType(Types.JDK.ANNOTATION_NAME), "test.Type"));

    TypeStructure structure = new TypeStructure(fields, annotations);
    instance = new CustomJFRType(1, TYPE_NAME, null, structure, constantPools, metadata);
  }

  @Test
  void typeSelfReferenceResolved() {
    for (TypedField field : instance.getFields()) {
      if (field.getName().equals("parent")) {
        assertNotEquals(CustomJFRType.SELF_TYPE, field.getType());
      }
    }
  }

  @Test
  void isBuiltin() {
    assertFalse(instance.isBuiltin());
  }

  @Test
  void getFields() {
    assertEquals(2, instance.getFields().size());
  }

  @Test
  void getField() {
    assertNotNull(instance.getField(FIELD_NAME));
    assertNotNull(instance.getField(PARENT_FIELD_NAME));
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
  void getAnnotations() {
    assertEquals(1, instance.getAnnotations().size());
  }

  @Test
  void canAccept() {
    for (Object builtinVal : BUILTIN_VALUE_MAP.values()) {
      assertFalse(instance.canAccept(builtinVal));
    }
    TypedValue value =
        instance.asValue(
            builder -> {
              builder.putField(FIELD_NAME, FIELD_VALUE);
            });
    assertTrue(instance.canAccept(value));
  }

  @Test
  void testEquality() {
    JFRType type1 = types.getType(Types.Builtin.STRING);
    JFRType type2 = types.getType(Types.Builtin.INT);
    JFRType type3 =
        types.getOrAdd(
            "type.Custom",
            t -> {
              t.addField("field1", types.getType(Types.Builtin.STRING))
                  .addField("field2", types.getType(Types.Builtin.STRING));
            });

    assertEquals(type1, type1);
    assertEquals(type2, type2);
    assertEquals(type3, type3);
    assertNotEquals(type1, type2);
    assertNotEquals(type1, type3);
    assertNotEquals(type2, type1);
    assertNotEquals(type2, type3);
    assertNotEquals(type3, type1);
    assertNotEquals(type3, type2);
  }
}
