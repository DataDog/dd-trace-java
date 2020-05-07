package com.datadog.profiling.jfr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class CustomTypeBuilderImplTest {
  private static final String FIELD_NAME = "field";
  private static final String CUSTOM_TYPE_NAME = "test.Type";
  private static final String ANNOTATION_TYPE_NAME = "jdk.jfr.Label";
  private static final String ANNOTATION_LABEL = "test.Label";

  private CustomTypeBuilderImpl instance;
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
        new CustomType(
            2,
            CUSTOM_TYPE_NAME,
            null,
            new TypeStructure(customTypeFields, Collections.emptyList()),
            constantPools,
            metadata);

    annotationType =
        new CustomType(
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
        .thenReturn(stringType);
    Mockito.when(types.getType(ArgumentMatchers.matches(CUSTOM_TYPE_NAME.replace(".", "\\."))))
        .thenReturn(customType);
    Mockito.when(types.getType(ArgumentMatchers.matches(ANNOTATION_TYPE_NAME.replace(".", "\\."))))
        .thenReturn(annotationType);

    instance = new CustomTypeBuilderImpl(types);
  }

  @Test
  void addFieldPredefined() {
    TypeStructure structure = instance.addField(FIELD_NAME, Types.Builtin.STRING).build();
    assertEquals(1, structure.fields.size());
    assertEquals(0, structure.annotations.size());

    TypedField field = structure.fields.get(0);
    assertEquals(FIELD_NAME, field.getName());
    assertEquals(stringType, field.getType());
  }

  @Test
  void testAddFieldCustom() {
    TypeStructure structure = instance.addField(FIELD_NAME, customType).build();
    assertEquals(1, structure.fields.size());

    TypedField field = structure.fields.get(0);
    assertEquals(FIELD_NAME, field.getName());
    assertEquals(customType, field.getType());
  }

  @Test
  void addFieldNullName() {
    assertThrows(NullPointerException.class, () -> instance.addField(null, Types.Builtin.STRING));
  }

  @Test
  void addFieldNullType() {
    assertThrows(
        NullPointerException.class, () -> instance.addField(FIELD_NAME, (Types.Predefined) null));
    assertThrows(NullPointerException.class, () -> instance.addField(FIELD_NAME, (Type) null));
  }

  @Test
  void addAnnotation() {
    TypeStructure structure = instance.addAnnotation(annotationType, ANNOTATION_LABEL).build();
    assertEquals(0, structure.fields.size());
    assertEquals(1, structure.annotations.size());

    Annotation annotation = structure.annotations.get(0);
    assertEquals(annotationType, annotation.type);
    assertEquals(ANNOTATION_LABEL, annotation.value);
  }

  @Test
  void addAnnotationNullValue() {
    TypeStructure structure = instance.addAnnotation(annotationType).build();
    assertEquals(0, structure.fields.size());
    assertEquals(1, structure.annotations.size());

    Annotation annotation = structure.annotations.get(0);
    assertEquals(annotationType, annotation.type);
    assertNull(annotation.value);
  }
}
