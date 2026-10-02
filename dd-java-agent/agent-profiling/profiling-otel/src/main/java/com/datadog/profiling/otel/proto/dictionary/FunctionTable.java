package com.datadog.profiling.otel.proto.dictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final int hash;

    FunctionKey(int nameIndex, int systemNameIndex, int filenameIndex, long startLine) {
      this.nameIndex = nameIndex;
      this.systemNameIndex = systemNameIndex;
      this.filenameIndex = filenameIndex;
      this.startLine = startLine;
      int h = nameIndex;
      h = 31 * h + systemNameIndex;
      h = 31 * h + filenameIndex;
      h = 31 * h + Long.hashCode(startLine);
      this.hash = h;
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
      return hash;
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

  public FunctionEntry get(int index) {
    return functions.get(index);
  }

  public int size() {
    return functions.size();
  }

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
