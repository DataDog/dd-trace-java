package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A fluent API for building custom type values lazily. */
public final class CustomTypeBuilder {

  private final Types types;
  private final List<TypedField> fields = new ArrayList<>();
  private final List<JFRAnnotation> annotations = new ArrayList<>();

  CustomTypeBuilder(Types types) {
    this.types = types;
  }

  public CustomTypeBuilder addField(String name, Types.Predefined type) {
    return addField(name, type, (JFRAnnotation[]) null);
  }

  public CustomTypeBuilder addField(
      String name, Types.Predefined type, JFRAnnotation... annotations) {
    return addField(name, types.getType(type), annotations);
  }

  public CustomTypeBuilder addField(String name, JFRType type) {
    return addField(name, type, (JFRAnnotation[]) null);
  }

  public CustomTypeBuilder addField(String name, JFRType type, JFRAnnotation... annotations) {
    fields.add(
        new TypedField(
            type,
            name,
            annotations != null ? Arrays.asList(annotations) : Collections.emptyList()));
    return this;
  }

  public CustomTypeBuilder addArrayField(String name, Types.Predefined type) {
    return addArrayField(name, type, (JFRAnnotation[]) null);
  }

  public CustomTypeBuilder addArrayField(
      String name, Types.Predefined type, JFRAnnotation... annotations) {
    return addArrayField(name, types.getType(type), annotations);
  }

  public CustomTypeBuilder addArrayField(String name, JFRType type) {
    return addArrayField(name, type, (JFRAnnotation[]) null);
  }

  public CustomTypeBuilder addArrayField(String name, JFRType type, JFRAnnotation... annotations) {
    fields.add(
        new TypedField(
            type,
            name,
            true,
            annotations != null ? Arrays.asList(annotations) : Collections.emptyList()));
    return this;
  }

  public CustomTypeBuilder addAnnotation(JFRType type, String value) {
    annotations.add(new JFRAnnotation(type, value));
    return this;
  }

  TypeStructure build() {
    return new TypeStructure(fields, annotations);
  }
}
