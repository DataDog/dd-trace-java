package com.datadog.profiling.otel.dictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * String interning table for OTLP profiles. Index 0 is reserved for the empty string (null/unset
 * sentinel).
 */
public final class StringTable {
  private final List<String> strings;
  private final Map<String, Integer> stringToIndex;

  public StringTable() {
    strings = new ArrayList<>();
    stringToIndex = new HashMap<>();
    // Index 0 is reserved for empty string (null/unset sentinel)
    strings.add("");
    stringToIndex.put("", 0);
  }

  /**
   * Interns a string and returns its index. If the string is already interned, returns the existing
   * index. Null strings are treated as empty strings and return index 0.
   *
   * @param s the string to intern
   * @return the index of the interned string
   */
  public int intern(String s) {
    if (s == null || s.isEmpty()) {
      return 0;
    }
    Integer existing = stringToIndex.get(s);
    if (existing != null) {
      return existing;
    }
    int index = strings.size();
    strings.add(s);
    stringToIndex.put(s, index);
    return index;
  }

  /**
   * Returns the string at the given index.
   *
   * @param index the index
   * @return the string at the index
   * @throws IndexOutOfBoundsException if index is out of bounds
   */
  public String get(int index) {
    return strings.get(index);
  }

  /**
   * Returns the number of interned strings (including the empty string at index 0).
   *
   * @return the size of the string table
   */
  public int size() {
    return strings.size();
  }

  /**
   * Returns an unmodifiable view of all interned strings.
   *
   * @return the list of interned strings
   */
  public List<String> getStrings() {
    return strings;
  }

  /** Resets the table to its initial state with only the empty string at index 0. */
  public void reset() {
    strings.clear();
    stringToIndex.clear();
    strings.add("");
    stringToIndex.put("", 0);
  }
}
