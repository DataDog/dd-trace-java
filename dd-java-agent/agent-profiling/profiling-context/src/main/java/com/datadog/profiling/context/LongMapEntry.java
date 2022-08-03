package com.datadog.profiling.context;

final class LongMapEntry<T> {
  final long key;
  final T value;

  LongMapEntry(long key, T value) {
    this.key = key;
    this.value = value;
  }
}
