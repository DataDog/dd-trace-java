package com.datadog.profiling.controller.jfr.parser;

/** Data structure mapping a long value to the type instance */
public interface LongMapping<T> {
  T getType(long value);
}
