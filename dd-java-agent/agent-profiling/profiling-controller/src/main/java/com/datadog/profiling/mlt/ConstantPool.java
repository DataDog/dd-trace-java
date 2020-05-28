package com.datadog.profiling.mlt;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.impl.factory.primitive.IntObjectMaps;
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps;

public final class ConstantPool<T> {
  private final MutableObjectIntMap<T> indexMap = ObjectIntMaps.mutable.ofInitialCapacity(128);
  private final MutableIntObjectMap<T> reverseIndexMap = IntObjectMaps.mutable.ofInitialCapacity(128);

  private final int offset;

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

  public int size() {
    return indexMap.size();
  }
}
