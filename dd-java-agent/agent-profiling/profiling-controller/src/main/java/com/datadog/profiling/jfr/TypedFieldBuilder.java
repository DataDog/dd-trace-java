package com.datadog.profiling.jfr;

public interface TypedFieldBuilder {
  TypedFieldBuilder addAnnotation(JFRType type);

  TypedFieldBuilder addAnnotation(JFRType type, String value);

  TypedFieldBuilder addAnnotation(Types.Predefined type);

  TypedFieldBuilder addAnnotation(Types.Predefined type, String value);

  TypedFieldBuilder asArray();
}
