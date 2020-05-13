package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class CompositeTypeBuilderImpl implements CompositeTypeBuilder {
  private final Types types;
  private final List<TypedField> fields = new ArrayList<>();
  private final List<Annotation> annotations = new ArrayList<>();

  CompositeTypeBuilderImpl(Types types) {
    this.types = types;
  }

  @Override
  public CompositeTypeBuilderImpl addField(String name, Types.Predefined type) {
    return addField(name, type, null);
  }

  @Override
  public CompositeTypeBuilderImpl addField(
      String name, Types.Predefined type, Consumer<TypedFieldBuilder> fieldCallback) {
    return addField(name, types.getType(type), fieldCallback);
  }

  @Override
  public CompositeTypeBuilderImpl addField(String name, Type type) {
    return addField(name, type, null);
  }

  @Override
  public CompositeTypeBuilderImpl addField(
      String name, Type type, Consumer<TypedFieldBuilder> fieldCallback) {
    TypedFieldBuilderImpl annotationsBuilder = new TypedFieldBuilderImpl(type, name, types);
    if (fieldCallback != null) {
      fieldCallback.accept(annotationsBuilder);
    }
    fields.add(annotationsBuilder.build());
    return this;
  }

  @Override
  public CompositeTypeBuilderImpl addAnnotation(Type type) {
    return addAnnotation(type, null);
  }

  @Override
  public CompositeTypeBuilderImpl addAnnotation(Type type, String value) {
    annotations.add(new Annotation(type, value));
    return this;
  }

  TypeStructure build() {
    return new TypeStructure(fields, annotations);
  }
}
