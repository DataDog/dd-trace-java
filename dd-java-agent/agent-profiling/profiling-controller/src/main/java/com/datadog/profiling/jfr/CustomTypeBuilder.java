package com.datadog.profiling.jfr;

import java.util.function.Consumer;

/** A fluent API for building custom type values lazily. */
public interface CustomTypeBuilder {
  CustomTypeBuilder addField(String name, Types.Predefined type);

  CustomTypeBuilder addField(
      String name, Types.Predefined type, Consumer<TypedFieldBuilder> fieldCallback);

  CustomTypeBuilder addField(String name, JFRType type);

  CustomTypeBuilder addField(String name, JFRType type, Consumer<TypedFieldBuilder> fieldCallback);

  CustomTypeBuilder addAnnotation(JFRType type);

  CustomTypeBuilder addAnnotation(JFRType type, String value);
}
