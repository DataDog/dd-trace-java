package com.datadog.profiling.jfr;

import java.util.function.Consumer;

/** A fluent API for lazy initialization of a custom type value */
public interface TypeValueBuilder {
  TypeValueBuilder putField(String name, byte value);

  TypeValueBuilder putField(String name, byte[] values);

  TypeValueBuilder putField(String name, char value);

  TypeValueBuilder putField(String name, char[] values);

  TypeValueBuilder putField(String name, short value);

  TypeValueBuilder putField(String name, short[] values);

  TypeValueBuilder putField(String name, int value);

  TypeValueBuilder putField(String name, int[] values);

  TypeValueBuilder putField(String name, long value);

  TypeValueBuilder putField(String name, long[] values);

  TypeValueBuilder putField(String name, float value);

  TypeValueBuilder putField(String name, float[] values);

  TypeValueBuilder putField(String name, double value);

  TypeValueBuilder putField(String name, double[] values);

  TypeValueBuilder putField(String name, boolean value);

  TypeValueBuilder putField(String name, boolean[] values);

  TypeValueBuilder putField(String name, String value);

  TypeValueBuilder putField(String name, String[] values);

  TypeValueBuilder putField(String name, TypedValue... values);

  TypeValueBuilder putField(String name, TypedValue value);

  TypeValueBuilder putField(String name, Consumer<TypeValueBuilder> fieldValueCallback);

  TypeValueBuilder putField(String name, Consumer<TypeValueBuilder>... fieldValueCallbacks);
}
