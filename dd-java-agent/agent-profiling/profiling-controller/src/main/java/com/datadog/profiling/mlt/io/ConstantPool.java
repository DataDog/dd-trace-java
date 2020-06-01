package com.datadog.profiling.mlt.io;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.impl.factory.primitive.IntObjectMaps;
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps;

public final class ConstantPool<T> {
  private final MutableObjectIntMap<T> indexMap = ObjectIntMaps.mutable.ofInitialCapacity(128);
  private final MutableIntObjectMap<T> reverseIndexMap =
      IntObjectMaps.mutable.ofInitialCapacity(128);

  private int offset;

  public ConstantPool() {
    this(0);
  }

  public ConstantPool(int reservedSlots) {
    this.offset = reservedSlots;
  }

  public T get(int index) {
    return index < 0 ? null : reverseIndexMap.get(index);
  }

  public int get(T constant) {
    if (constant == null) {
      return -1;
    }
    int idx = indexMap.getIfAbsentPut(constant, indexMap.size() + offset);
    reverseIndexMap.put(idx, constant);
    return idx;
  }

  public void insert(int ptr, T constant) {
    if (constant == null) {
      if (ptr != -1) {
        throw new IllegalArgumentException();
      }
      return;
    }
    indexMap.put(constant, ptr);
    reverseIndexMap.put(ptr, constant);
    offset = Math.max(offset, ptr + 1);
  }

  public int size() {
    return indexMap.size();
  }
}
