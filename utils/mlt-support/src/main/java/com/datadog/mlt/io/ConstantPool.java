package com.datadog.mlt.io;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * A generic constant pool implementation.
 *
 * @param <T> constant type
 */
public final class ConstantPool<T> {
  private final Object2IntMap<T> indexMap = new Object2IntArrayMap<>(128);
  private final Int2ObjectMap<T> reverseIndexMap = new Int2ObjectArrayMap<>(128);

  private int offset;

  public ConstantPool() {
    this(0);
  }

  /**
   * Create a new instance with the constant index starting at {@code startingIndex}
   *
   * @param startingIndex the index of the first inserted constant value
   */
  public ConstantPool(int startingIndex) {
    this.offset = startingIndex;
  }

  /**
   * Retrieve the constant by its index
   *
   * @param index constant index
   * @return the constant value if the index >=0 and such mapping exists; {@literal null} otherwise
   */
  public T get(int index) {
    return index < 0 ? null : reverseIndexMap.get(index);
  }

  /**
   * Retrieve the constant index. Add the constant if it does not exist in the pool yet.
   *
   * @param constant the constant value
   * @return a valid constant index (>=0) or {@literal -1} for {@literal null} value
   */
  public int getOrInsert(T constant) {
    if (constant == null) {
      return -1;
    }
    int idx = indexMap.computeIntIfAbsent(constant, k -> indexMap.size() + offset);
    reverseIndexMap.put(idx, constant);
    return idx;
  }

  /**
   * Insert a constant value with the given index
   *
   * @param ptr constant index
   * @param constant constant value
   * @throws IllegalArgumentException if the constant value is {@literal null} and index is not
   *     {@literal -1}
   */
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

  /** @return the number of constants in the pool */
  public int size() {
    return indexMap.size();
  }
}
