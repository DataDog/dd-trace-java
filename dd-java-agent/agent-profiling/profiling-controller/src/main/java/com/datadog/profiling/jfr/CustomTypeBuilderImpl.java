package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class CustomTypeBuilderImpl implements CustomTypeBuilder {
  private final Types types;
  private final List<TypedField> fields = new ArrayList<>();
  private final List<JFRAnnotation> annotations = new ArrayList<>();

  CustomTypeBuilderImpl(Types types) {
    this.types = types;
  }

  @Override
  public CustomTypeBuilderImpl addField(String name, Types.Predefined type) {
    return addField(name, type, null);
  }

  @Override
  public CustomTypeBuilderImpl addField(
      String name, Types.Predefined type, Consumer<TypedFieldBuilder> fieldCallback) {
    return addField(name, types.getType(type), fieldCallback);
  }

  @Override
  public CustomTypeBuilderImpl addField(String name, JFRType type) {
    return addField(name, type, null);
  }

  @Override
  public CustomTypeBuilderImpl addField(
      String name, JFRType type, Consumer<TypedFieldBuilder> fieldCallback) {
    TypedFieldBuilderImpl annotationsBuilder = new TypedFieldBuilderImpl(type, name, types);
    if (fieldCallback != null) {
      fieldCallback.accept(annotationsBuilder);
    }
    fields.add(annotationsBuilder.build());
    return this;
  }

  @Override
  public CustomTypeBuilderImpl addAnnotation(JFRType type) {
    return addAnnotation(type, null);
  }

  @Override
  public CustomTypeBuilderImpl addAnnotation(JFRType type, String value) {
    annotations.add(new JFRAnnotation(type, value));
    return this;
  }

  TypeStructure build() {
    return new TypeStructure(fields, annotations);
  }
}
