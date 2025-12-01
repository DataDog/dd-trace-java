package com.datadog.profiling.otel.dictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack (call stack) deduplication table for OTLP profiles. Index 0 is reserved for the null/unset
 * stack. A stack is an ordered sequence of location indices, where the first entry is the leaf
 * frame.
 */
public final class StackTable {

  /** Wrapper for int[] to use as HashMap key with proper equals/hashCode. */
  private static final class StackKey {
    final int[] locationIndices;
    private final int hashCode;

    StackKey(int[] locationIndices) {
      this.locationIndices = locationIndices;
      this.hashCode = Arrays.hashCode(locationIndices);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      StackKey that = (StackKey) o;
      return Arrays.equals(locationIndices, that.locationIndices);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /** Stack entry stored in the table. */
  public static final class StackEntry {
    public final int[] locationIndices;

    StackEntry(int[] locationIndices) {
      this.locationIndices = locationIndices;
    }
  }

  private final List<StackEntry> stacks;
  private final Map<StackKey, Integer> stackToIndex;

  public StackTable() {
    stacks = new ArrayList<>();
    stackToIndex = new HashMap<>();
    // Index 0 is reserved for null/unset stack (empty)
    stacks.add(new StackEntry(new int[0]));
  }

  /**
   * Interns a stack and returns its index. If the stack is already interned, returns the existing
   * index. An empty or null array returns index 0.
   *
   * @param locationIndices array of location indices (first entry is leaf frame)
   * @return the index of the interned stack
   */
  public int intern(int[] locationIndices) {
    if (locationIndices == null || locationIndices.length == 0) {
      return 0;
    }

    StackKey key = new StackKey(locationIndices);
    Integer existing = stackToIndex.get(key);
    if (existing != null) {
      return existing;
    }

    int index = stacks.size();
    // Make a defensive copy
    int[] copy = Arrays.copyOf(locationIndices, locationIndices.length);
    stacks.add(new StackEntry(copy));
    stackToIndex.put(new StackKey(copy), index);
    return index;
  }

  /**
   * Returns the stack entry at the given index.
   *
   * @param index the index
   * @return the stack entry
   * @throws IndexOutOfBoundsException if index is out of bounds
   */
  public StackEntry get(int index) {
    return stacks.get(index);
  }

  /**
   * Returns the number of stacks (including the null stack at index 0).
   *
   * @return the size of the stack table
   */
  public int size() {
    return stacks.size();
  }

  /**
   * Returns the list of all stack entries.
   *
   * @return the list of stack entries
   */
  public List<StackEntry> getStacks() {
    return stacks;
  }

  /** Resets the table to its initial state with only the null stack at index 0. */
  public void reset() {
    stacks.clear();
    stackToIndex.clear();
    stacks.add(new StackEntry(new int[0]));
  }
}
