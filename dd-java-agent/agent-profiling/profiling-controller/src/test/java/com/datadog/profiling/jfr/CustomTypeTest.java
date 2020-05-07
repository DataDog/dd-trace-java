package com.datadog.profiling.jfr;

import static com.datadog.profiling.jfr.TypeUtils.BUILTIN_VALUE_MAP;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomTypeTest {
  private static final String TYPE_NAME = "test.Type";
  private static final String FIELD_NAME = "field1";
  private static final String PARENT_FIELD_NAME = "parent";
  private static final String FIELD_VALUE = "hello";
  public static final int TYPE_ID = 1;
  private CustomType instance;
  private Types types;

  @BeforeEach
  void setUp() {
    ConstantPools constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);

    List<TypedField> fields = new ArrayList<>();
    List<Annotation> annotations = new ArrayList<>();

    fields.add(new TypedField(types.getType(Types.Builtin.STRING), FIELD_NAME));
    fields.add(new TypedField(SelfType.INSTANCE, PARENT_FIELD_NAME));
    annotations.add(new Annotation(types.getType(Types.JDK.ANNOTATION_NAME), "test.Type"));

    TypeStructure structure = new TypeStructure(fields, annotations);
    instance = new CustomType(TYPE_ID, TYPE_NAME, null, structure, constantPools, metadata);
  }

  @Test
  void typeSelfReferenceResolved() {
    for (TypedField field : instance.getFields()) {
      if (field.getName().equals("parent")) {
        assertNotEquals(SelfType.INSTANCE, field.getType());
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
    assertEquals(value, instance.nullValue());
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
  void getId() {
    assertEquals(TYPE_ID, instance.getId());
  }

  @Test
  void getTypeName() {
    assertEquals(TYPE_NAME, instance.getTypeName());
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
    assertNotNull(instance.getConstantPool());
  }

  @Test
  void isUsedBySimple() {
    Type other = types.getType(Types.Builtin.STRING);

    // has a self-referenced field
    assertTrue(instance.isUsedBy(instance));
    assertFalse(instance.isUsedBy(other));
    assertTrue(other.isUsedBy(instance));
  }

  @Test
  void isUsedByComplex() {
    Type target = types.getType(Types.Builtin.INT);

    Type main =
        types.getOrAdd(
            "custom.Main",
            type -> {
              type.addField("parent", type.selfType())
                  .addField("field", Types.Builtin.STRING)
                  .addField("other", types.getType("custom.Other", true));
            });

    Type other =
        types.getOrAdd(
            "custom.Other",
            type -> {
              type.addField("loopback", main).addField("field", Types.Builtin.INT);
            });
    types.resolveAll();

    // has a self-referenced field
    assertTrue(main.isUsedBy(main));
    assertTrue(main.isUsedBy(other));
    assertTrue(target.isUsedBy(main));
  }

  @Test
  void testEquality() {
    Type type1 = types.getType(Types.Builtin.STRING);
    Type type2 = types.getType(Types.Builtin.INT);
    Type type3 =
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
