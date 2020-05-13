package com.datadog.profiling.jfr;

import java.util.function.Consumer;

/** A fluent API for lazy initialization of a composite type value */
public interface TypeValueBuilder {
  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, byte value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, byte[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, char value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, char[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, short value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, short[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, int value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, int[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, long value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, long[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, float value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, float[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, double value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, double[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, boolean value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, boolean[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, String value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, String[] values);

  /**
   * Put a named field value
   *
   * @param name field name
   * @param value field value
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, TypedValue value);

  /**
   * Put a named field array of values
   *
   * @param name field name
   * @param values field values
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, TypedValue... values);

  /**
   * Put a named field lazily evaluated value
   *
   * @param name field name
   * @param fieldValueCallback field value builder
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, Consumer<TypeValueBuilder> fieldValueCallback);

  /**
   * Put a named field array of lazily evaluated values
   *
   * @param name field name
   * @param fieldValueCallbacks field value builders
   * @return a {@linkplain TypeValueBuilder} instance for invocation chaining
   */
  TypeValueBuilder putField(String name, Consumer<TypeValueBuilder>... fieldValueCallbacks);
}
