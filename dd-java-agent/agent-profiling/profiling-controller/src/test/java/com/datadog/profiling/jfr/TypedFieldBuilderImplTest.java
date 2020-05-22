package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class TypedFieldBuilderImplTest {
  private static final String FIELD_NAME = "field";
  private static final String CUSTOM_TYPE_NAME = "test.Type";
  private static final String ANNOTATION_TYPE_NAME = "jdk.jfr.Label";
  private static final String ANNOTATION_LABEL = "test.Label";

  private TypedFieldBuilderImpl instance;
  private Type stringType;
  private Type customType;
  private Type annotationType;

  @BeforeEach
  void setUp() {
    Metadata metadata = Mockito.mock(Metadata.class);
    ConstantPools constantPools = Mockito.mock(ConstantPools.class);

    stringType = new BuiltinType(1, Types.Builtin.STRING, constantPools, metadata);

    List<TypedField> customTypeFields =
        Collections.singletonList(new TypedField(stringType, "item"));
    customType =
        new CompositeType(
            2,
            CUSTOM_TYPE_NAME,
            null,
            new TypeStructure(customTypeFields, Collections.emptyList()),
            constantPools,
            metadata);

    annotationType =
        new CompositeType(
            3,
            ANNOTATION_TYPE_NAME,
            Annotation.ANNOTATION_SUPER_TYPE_NAME,
            new TypeStructure(
                Collections.singletonList(new TypedField(stringType, "value")),
                Collections.emptyList()),
            constantPools,
            metadata);

    Types types = Mockito.mock(Types.class);
    Mockito.when(types.getType(ArgumentMatchers.any(Types.Predefined.class)))
        .thenAnswer(
            i ->
                ((Types.Predefined) i.getArgument(0)).getTypeName().equals(ANNOTATION_TYPE_NAME)
                    ? annotationType
                    : stringType);
    Mockito.when(types.getType(ArgumentMatchers.matches(CUSTOM_TYPE_NAME.replace(".", "\\."))))
        .thenReturn(customType);
    Mockito.when(types.getType(ArgumentMatchers.matches(ANNOTATION_TYPE_NAME.replace(".", "\\."))))
        .thenReturn(annotationType);

    instance = new TypedFieldBuilderImpl(customType, FIELD_NAME, types);
  }

  @Test
  void addAnnotationNullValue() {
    TypedField field = instance.addAnnotation(annotationType).build();

    assertNotNull(field);
    assertEquals(FIELD_NAME, field.getName());
    assertEquals(customType, field.getType());

    assertEquals(1, field.getAnnotations().size());
    Annotation annotation = field.getAnnotations().get(0);
    assertEquals(annotationType, annotation.getType());
    assertNull(annotation.getValue());
  }

  @Test
  void addPredefinedAnnotationNullValue() {
    TypedField field = instance.addAnnotation(Types.JDK.ANNOTATION_LABEL).build();

    assertNotNull(field);
    assertEquals(FIELD_NAME, field.getName());
    assertEquals(customType, field.getType());

    assertEquals(1, field.getAnnotations().size());
    Annotation annotation = field.getAnnotations().get(0);
    assertEquals(annotationType, annotation.getType());
    assertNull(annotation.getValue());
  }

  @Test
  void addAnnotationValue() {
    TypedField field = instance.addAnnotation(annotationType, ANNOTATION_LABEL).build();

    assertNotNull(field);
    assertEquals(FIELD_NAME, field.getName());
    assertEquals(customType, field.getType());

    assertEquals(1, field.getAnnotations().size());
    Annotation annotation = field.getAnnotations().get(0);
    assertEquals(annotationType, annotation.getType());
    assertEquals(ANNOTATION_LABEL, annotation.getValue());
  }

  @Test
  void addPredefinedAnnotationValue() {
    TypedField field = instance.addAnnotation(Types.JDK.ANNOTATION_LABEL, ANNOTATION_LABEL).build();

    assertNotNull(field);
    assertEquals(FIELD_NAME, field.getName());
    assertEquals(customType, field.getType());

    assertEquals(1, field.getAnnotations().size());
    Annotation annotation = field.getAnnotations().get(0);
    assertEquals(annotationType, annotation.getType());
    assertEquals(ANNOTATION_LABEL, annotation.getValue());
  }

  @Test
  void asArray() {
    TypedField field = instance.asArray().build();

    assertNotNull(field);
    assertTrue(field.isArray());
  }
}
