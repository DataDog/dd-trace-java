package com.datadog.profiling.otel.proto.dictionary;

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

  public String get(int index) {
    return strings.get(index);
  }

  public int size() {
    return strings.size();
  }

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
