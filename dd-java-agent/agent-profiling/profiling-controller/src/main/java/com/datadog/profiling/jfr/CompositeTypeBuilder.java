package com.datadog.profiling.jfr;

import java.util.function.Consumer;

/** A fluent API for building composite types lazily. */
public interface CompositeTypeBuilder {
  /**
   * Add a field of the given name and (predefined) type
   *
   * @param name the field name
   * @param type the field type
   * @return a {@linkplain CompositeTypeBuilder} instance for invocation chaining
   */
  CompositeTypeBuilder addField(String name, Types.Predefined type);

  /**
   * Add a field of the given name and (predefined) type and with a customization callback
   *
   * @param name the field name
   * @param type the field type
   * @param fieldCallback the field customization callback
   * @return a {@linkplain CompositeTypeBuilder} instance for invocation chaining
   */
  CompositeTypeBuilder addField(
      String name, Types.Predefined type, Consumer<TypedFieldBuilder> fieldCallback);

  /**
   * Add a field of the given name and type
   *
   * @param name the field name
   * @param type the field type
   * @return a {@linkplain CompositeTypeBuilder} instance for invocation chaining
   */
  CompositeTypeBuilder addField(String name, Type type);

  /**
   * Add a field of the given name and type and with a customization callback
   *
   * @param name the field name
   * @param type the field type
   * @param fieldCallback the field customization callback
   * @return a {@linkplain CompositeTypeBuilder} instance for invocation chaining
   */
  CompositeTypeBuilder addField(String name, Type type, Consumer<TypedFieldBuilder> fieldCallback);

  /**
   * Add an annotation of the given type
   *
   * @param type the annotation type
   * @return a {@linkplain CompositeTypeBuilder} instance for invocation chaining
   */
  CompositeTypeBuilder addAnnotation(Type type);

  /**
   * Add an annotation of the given type and with the given value
   *
   * @param type the annotation type
   * @param value the annotation value
   * @return a {@linkplain CompositeTypeBuilder} instance for invocation chaining
   */
  CompositeTypeBuilder addAnnotation(Type type, String value);

  /**
   * A special placeholder type to refer to the type being currently built (otherwise impossible
   * because the type is not yet ready).
   *
   * @return {@linkplain SelfType#INSTANCE}
   */
  default Type selfType() {
    return SelfType.INSTANCE;
  }
}
