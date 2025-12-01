package com.datadog.profiling.otel.dictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Function deduplication table for OTLP profiles. Index 0 is reserved for the null/unset function.
 * Functions are identified by their (nameIndex, systemNameIndex, filenameIndex, startLine) tuple.
 */
public final class FunctionTable {

  /** Immutable key for function lookup. */
  private static final class FunctionKey {
    final int nameIndex;
    final int systemNameIndex;
    final int filenameIndex;
    final long startLine;

    FunctionKey(int nameIndex, int systemNameIndex, int filenameIndex, long startLine) {
      this.nameIndex = nameIndex;
      this.systemNameIndex = systemNameIndex;
      this.filenameIndex = filenameIndex;
      this.startLine = startLine;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      FunctionKey that = (FunctionKey) o;
      return nameIndex == that.nameIndex
          && systemNameIndex == that.systemNameIndex
          && filenameIndex == that.filenameIndex
          && startLine == that.startLine;
    }

    @Override
    public int hashCode() {
      return Objects.hash(nameIndex, systemNameIndex, filenameIndex, startLine);
    }
  }

  /** Function entry stored in the table. */
  public static final class FunctionEntry {
    public final int nameIndex;
    public final int systemNameIndex;
    public final int filenameIndex;
    public final long startLine;

    FunctionEntry(int nameIndex, int systemNameIndex, int filenameIndex, long startLine) {
      this.nameIndex = nameIndex;
      this.systemNameIndex = systemNameIndex;
      this.filenameIndex = filenameIndex;
      this.startLine = startLine;
    }
  }

  private final List<FunctionEntry> functions;
  private final Map<FunctionKey, Integer> functionToIndex;

  public FunctionTable() {
    functions = new ArrayList<>();
    functionToIndex = new HashMap<>();
    // Index 0 is reserved for null/unset function
    functions.add(new FunctionEntry(0, 0, 0, 0));
  }

  /**
   * Interns a function and returns its index. If the function is already interned, returns the
   * existing index.
   *
   * @param nameIndex index into string table for human-readable name
   * @param systemNameIndex index into string table for system name (e.g., mangled name)
   * @param filenameIndex index into string table for source filename
   * @param startLine starting line number in source (0 = unset)
   * @return the index of the interned function
   */
  public int intern(int nameIndex, int systemNameIndex, int filenameIndex, long startLine) {
    // All zeros means null function
    if (nameIndex == 0 && systemNameIndex == 0 && filenameIndex == 0 && startLine == 0) {
      return 0;
    }

    FunctionKey key = new FunctionKey(nameIndex, systemNameIndex, filenameIndex, startLine);
    Integer existing = functionToIndex.get(key);
    if (existing != null) {
      return existing;
    }

    int index = functions.size();
    functions.add(new FunctionEntry(nameIndex, systemNameIndex, filenameIndex, startLine));
    functionToIndex.put(key, index);
    return index;
  }

  /**
   * Returns the function entry at the given index.
   *
   * @param index the index
   * @return the function entry
   * @throws IndexOutOfBoundsException if index is out of bounds
   */
  public FunctionEntry get(int index) {
    return functions.get(index);
  }

  /**
   * Returns the number of functions (including the null function at index 0).
   *
   * @return the size of the function table
   */
  public int size() {
    return functions.size();
  }

  /**
   * Returns the list of all function entries.
   *
   * @return the list of function entries
   */
  public List<FunctionEntry> getFunctions() {
    return functions;
  }

  /** Resets the table to its initial state with only the null function at index 0. */
  public void reset() {
    functions.clear();
    functionToIndex.clear();
    functions.add(new FunctionEntry(0, 0, 0, 0));
  }
}
