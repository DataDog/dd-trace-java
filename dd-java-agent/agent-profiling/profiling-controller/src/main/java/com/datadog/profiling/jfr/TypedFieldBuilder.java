package com.datadog.profiling.jfr;

public interface TypedFieldBuilder {
  TypedFieldBuilder addAnnotation(Type type);

  TypedFieldBuilder addAnnotation(Type type, String value);

  TypedFieldBuilder addAnnotation(Types.Predefined type);

  TypedFieldBuilder addAnnotation(Types.Predefined type, String value);

  TypedFieldBuilder asArray();
}
