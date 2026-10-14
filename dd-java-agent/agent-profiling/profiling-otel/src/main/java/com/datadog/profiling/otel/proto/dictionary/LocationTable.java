package com.datadog.profiling.otel.proto.dictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Location deduplication table for OTLP profiles. Index 0 is reserved for the null/unset location.
 * A location represents a stack frame with mapping, address, and line information.
 */
public final class LocationTable {

  /** Immutable key for location lookup. */
  private static final class LocationKey {
    final int mappingIndex;
    final long address;
    final int functionIndex;
    final long line;
    final long column;
    private final int hash;

    LocationKey(int mappingIndex, long address, int functionIndex, long line, long column) {
      this.mappingIndex = mappingIndex;
      this.address = address;
      this.functionIndex = functionIndex;
      this.line = line;
      this.column = column;
      int h = mappingIndex;
      h = 31 * h + Long.hashCode(address);
      h = 31 * h + functionIndex;
      h = 31 * h + Long.hashCode(line);
      h = 31 * h + Long.hashCode(column);
      this.hash = h;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LocationKey that = (LocationKey) o;
      return mappingIndex == that.mappingIndex
          && address == that.address
          && functionIndex == that.functionIndex
          && line == that.line
          && column == that.column;
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  /** Line information within a location (for inlined functions). */
  public static final class LineEntry {
    public final int functionIndex;
    public final long line;
    public final long column;

    public LineEntry(int functionIndex, long line, long column) {
      this.functionIndex = functionIndex;
      this.line = line;
      this.column = column;
    }
  }

  /** Location entry stored in the table. */
  public static final class LocationEntry {
    public final int mappingIndex;
    public final long address;
    public final List<LineEntry> lines;

    LocationEntry(int mappingIndex, long address, List<LineEntry> lines) {
      this.mappingIndex = mappingIndex;
      this.address = address;
      this.lines = lines;
    }
  }

  private final List<LocationEntry> locations;
  private final Map<LocationKey, Integer> locationToIndex;

  public LocationTable() {
    locations = new ArrayList<>();
    locationToIndex = new HashMap<>();
    // Index 0 is reserved for null/unset location
    locations.add(new LocationEntry(0, 0, new ArrayList<>()));
  }

  public int intern(int mappingIndex, long address, int functionIndex, long line, long column) {
    // All zeros means null location
    if (mappingIndex == 0 && address == 0 && functionIndex == 0 && line == 0 && column == 0) {
      return 0;
    }

    LocationKey key = new LocationKey(mappingIndex, address, functionIndex, line, column);
    Integer existing = locationToIndex.get(key);
    if (existing != null) {
      return existing;
    }

    int index = locations.size();
    List<LineEntry> lines = new ArrayList<>();
    if (functionIndex != 0 || line != 0 || column != 0) {
      lines.add(new LineEntry(functionIndex, line, column));
    }
    locations.add(new LocationEntry(mappingIndex, address, lines));
    locationToIndex.put(key, index);
    return index;
  }

  public LocationEntry get(int index) {
    return locations.get(index);
  }

  public int size() {
    return locations.size();
  }

  public List<LocationEntry> getLocations() {
    return locations;
  }

  /** Resets the table to its initial state with only the null location at index 0. */
  public void reset() {
    locations.clear();
    locationToIndex.clear();
    locations.add(new LocationEntry(0, 0, new ArrayList<>()));
  }
}
