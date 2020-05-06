package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.List;

final class TypedFieldBuilderImpl implements TypedFieldBuilder {
  private final Types types;
  private final List<JFRAnnotation> annotations = new ArrayList<>();
  private final JFRType type;
  private final String name;
  private boolean asArray;

  TypedFieldBuilderImpl(JFRType type, String name, Types types) {
    this.type = type;
    this.name = name;
    this.types = types;
  }

  @Override
  public TypedFieldBuilderImpl addAnnotation(JFRType type) {
    return addAnnotation(type, null);
  }

  @Override
  public TypedFieldBuilderImpl addAnnotation(JFRType type, String value) {
    annotations.add(new JFRAnnotation(type, value));
    return this;
  }

  @Override
  public TypedFieldBuilderImpl addAnnotation(Types.Predefined type) {
    return addAnnotation(types.getType(type));
  }

  @Override
  public TypedFieldBuilderImpl addAnnotation(Types.Predefined type, String value) {
    return addAnnotation(types.getType(type), value);
  }

  @Override
  public TypedFieldBuilderImpl asArray() {
    asArray = true;
    return this;
  }

  TypedField build() {
    return new TypedField(type, name, asArray, annotations);
  }
}
