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
  private static final String FIELD_VALUE = "hello";
  private CustomJFRType instance;

  @BeforeEach
  void setUp() {
    ConstantPools constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    Types types = new Types(metadata);

    List<TypedField> fields = new ArrayList<>();
    List<JFRAnnotation> annotations = new ArrayList<>();

    fields.add(new TypedField(types.getType(Types.Builtin.STRING), FIELD_NAME));
    fields.add(new TypedField(CustomJFRType.SELF_TYPE, "parent"));
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
}
