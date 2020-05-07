package com.datadog.profiling.jfr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypedFieldTest {
  private static final String FIELD_NAME = "instance";

  private Types types;

  @BeforeEach
  void setUp() {
    ConstantPools constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);
  }

  @Test
  void getCustomTypeNoAnnotations() {
    Type type =
        types.getOrAdd(
            "custom.Type",
            t -> {
              t.addField("field", Types.Builtin.STRING)
                  .addAnnotation(types.getType(Types.JDK.ANNOTATION_LABEL), "custom.Type");
            });
    TypedField instance = new TypedField(type, FIELD_NAME);

    assertEquals(FIELD_NAME, instance.getName());
    assertEquals(type, instance.getType());
    assertEquals(0, instance.getAnnotations().size());
    assertFalse(instance.isArray());
  }

  @Test
  void getCustomTypeAnnotations() {
    Type type =
        types.getOrAdd(
            "custom.Type",
            t -> {
              t.addField("field", Types.Builtin.STRING)
                  .addAnnotation(types.getType(Types.JDK.ANNOTATION_LABEL), "custom.Type");
            });
    TypedField instance =
        new TypedField(
            type,
            FIELD_NAME,
            false,
            Collections.singletonList(
                new Annotation(types.getType(Types.JDK.ANNOTATION_LABEL), "field")));

    assertEquals(FIELD_NAME, instance.getName());
    assertEquals(type, instance.getType());
    assertEquals(1, instance.getAnnotations().size());
    assertFalse(instance.isArray());
  }

  @Test
  void getCustomTypeArrayNoAnnotations() {
    Type type =
        types.getOrAdd(
            "custom.Type",
            t -> {
              t.addField("field", Types.Builtin.STRING)
                  .addAnnotation(types.getType(Types.JDK.ANNOTATION_LABEL), "custom.Type");
            });
    TypedField instance = new TypedField(type, FIELD_NAME, true);

    assertEquals(FIELD_NAME, instance.getName());
    assertEquals(type, instance.getType());
    assertEquals(0, instance.getAnnotations().size());
    assertTrue(instance.isArray());
  }

  @Test
  void getCustomTypeArrayAnnotations() {
    Type type =
        types.getOrAdd(
            "custom.Type",
            t -> {
              t.addField("field", Types.Builtin.STRING)
                  .addAnnotation(types.getType(Types.JDK.ANNOTATION_LABEL), "custom.Type");
            });
    TypedField instance =
        new TypedField(
            type,
            FIELD_NAME,
            true,
            Collections.singletonList(
                new Annotation(types.getType(Types.JDK.ANNOTATION_LABEL), "field")));

    assertEquals(FIELD_NAME, instance.getName());
    assertEquals(type, instance.getType());
    assertEquals(1, instance.getAnnotations().size());
    assertTrue(instance.isArray());
  }

  @Test
  void equality() {
    Type[] fieldTypes =
        new Type[] {
          types.getOrAdd(
              "custom.Type",
              t -> {
                t.addField("field", Types.Builtin.STRING)
                    .addAnnotation(types.getType(Types.JDK.ANNOTATION_LABEL), "custom.Type");
              }),
          types.getType(Types.Builtin.STRING)
        };

    String[] fieldNames = new String[] {"field1", "field2"};
    boolean[] arrayFlags = new boolean[] {true, false};
    List<List<Annotation>> annotations =
        Arrays.asList(
            Collections.emptyList(),
            Collections.singletonList(
                new Annotation(types.getType(Types.JDK.ANNOTATION_LABEL), "field")));

    List<TypedField> fields = new ArrayList<>();
    for (Type fieldType : fieldTypes) {
      for (String fieldName : fieldNames) {
        for (boolean arrayFlag : arrayFlags) {
          for (List<Annotation> annotationList : annotations) {
            fields.add(new TypedField(fieldType, fieldName, arrayFlag, annotationList));
          }
        }
      }
    }

    for (TypedField field1 : fields) {
      // make the code coverage check happy
      assertFalse(field1.equals(10));

      assertFalse(field1.equals(null));
      for (TypedField field2 : fields) {
        assertEquals(field1 == field2, field1.equals(field2));

        // keep the hashCode-equals contract
        assertTrue(field1.hashCode() != field2.hashCode() || field1.equals(field2));
      }
    }
  }
}
