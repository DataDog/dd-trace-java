package com.datadog.profiling.jfr;

import java.util.function.Consumer;

/** A fluent API for building custom type values lazily. */
public interface CustomTypeBuilder {
  CustomTypeBuilder addField(String name, Types.Predefined type);

  CustomTypeBuilder addField(
      String name, Types.Predefined type, Consumer<TypedFieldBuilder> fieldCallback);

  CustomTypeBuilder addField(String name, Type type);

  CustomTypeBuilder addField(String name, Type type, Consumer<TypedFieldBuilder> fieldCallback);

  CustomTypeBuilder addAnnotation(Type type);

  CustomTypeBuilder addAnnotation(Type type, String value);

  default Type selfType() {
    return SelfType.INSTANCE;
  }
}
