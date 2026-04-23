package com.datadog.profiling.otel.proto.dictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Link deduplication table for OTLP profiles. Index 0 is reserved for the null/unset link. A link
 * connects a profile sample to a trace span for correlation.
 */
public final class LinkTable {

  /** Open-addressing map keyed on two longs (traceIdLow, spanId). */
  private static final class LongLongToIntMap {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys1;
    private long[] keys2;
    private int[] values;
    private int mask;
    private int size;

    LongLongToIntMap(int initialCapacity) {
      int cap = Integer.highestOneBit(Math.max(initialCapacity * 2, 16) - 1) << 1;
      keys1 = new long[cap];
      keys2 = new long[cap];
      values = new int[cap];
      Arrays.fill(keys1, EMPTY);
      mask = cap - 1;
    }

    int get(long k1, long k2) {
      int slot = (int) (mix(k1 ^ k2) & mask);
      while (keys1[slot] != EMPTY) {
        if (keys1[slot] == k1 && keys2[slot] == k2) return values[slot];
        slot = (slot + 1) & mask;
      }
      return -1;
    }

    void put(long k1, long k2, int value) {
      if (size * 2 >= mask) resize();
      int slot = (int) (mix(k1 ^ k2) & mask);
      while (keys1[slot] != EMPTY) {
        if (keys1[slot] == k1 && keys2[slot] == k2) {
          values[slot] = value;
          return;
        }
        slot = (slot + 1) & mask;
      }
      keys1[slot] = k1;
      keys2[slot] = k2;
      values[slot] = value;
      size++;
    }

    void clear() {
      Arrays.fill(keys1, EMPTY);
      size = 0;
    }

    private void resize() {
      long[] oldKeys1 = keys1;
      long[] oldKeys2 = keys2;
      int[] oldValues = values;
      int newCap = (mask + 1) * 2;
      keys1 = new long[newCap];
      keys2 = new long[newCap];
      values = new int[newCap];
      Arrays.fill(keys1, EMPTY);
      mask = newCap - 1;
      size = 0;
      for (int i = 0; i < oldKeys1.length; i++) {
        if (oldKeys1[i] != EMPTY) put(oldKeys1[i], oldKeys2[i], oldValues[i]);
      }
    }

    private static long mix(long key) {
      key ^= key >>> 33;
      key *= 0xff51afd7ed558ccdL;
      key ^= key >>> 33;
      return key;
    }
  }

  /** Link entry stored in the table. */
  public static final class LinkEntry {
    public final byte[] traceId;
    public final byte[] spanId;

    LinkEntry(byte[] traceId, byte[] spanId) {
      this.traceId = traceId;
      this.spanId = spanId;
    }
  }

  private static final byte[] EMPTY_TRACE_ID = new byte[16];
  private static final byte[] EMPTY_SPAN_ID = new byte[8];

  private final List<LinkEntry> links;
  private final LongLongToIntMap linkToIndex;

  public LinkTable() {
    links = new ArrayList<>();
    linkToIndex = new LongLongToIntMap(16);
    // Index 0 is reserved for null/unset link
    links.add(new LinkEntry(EMPTY_TRACE_ID, EMPTY_SPAN_ID));
  }

  /**
   * Interns a link and returns its index. If the link is already interned, returns the existing
   * index. All-zero trace/span IDs return index 0.
   *
   * @param traceId 16-byte trace identifier
   * @param spanId 8-byte span identifier
   * @return the index of the interned link
   */
  public int intern(byte[] traceId, byte[] spanId) {
    if (traceId == null || spanId == null) {
      return 0;
    }
    // Extract key longs from byte arrays (traceId lower 64 bits = bytes 8-15)
    long traceIdLow = bytesToLong(traceId, 8);
    long spanIdLong = bytesToLong(spanId, 0);
    if (traceIdLow == 0 && spanIdLong == 0) {
      return 0;
    }

    int cached = linkToIndex.get(traceIdLow, spanIdLong);
    if (cached != -1) return cached;

    int index = links.size();
    byte[] traceIdCopy = Arrays.copyOf(traceId, traceId.length);
    byte[] spanIdCopy = Arrays.copyOf(spanId, spanId.length);
    links.add(new LinkEntry(traceIdCopy, spanIdCopy));
    linkToIndex.put(traceIdLow, spanIdLong, index);
    return index;
  }

  /**
   * Interns a link from 64-bit span and trace IDs. The trace ID is placed in the lower 64 bits of
   * the 128-bit OTLP trace ID.
   *
   * @param traceIdLow lower 64 bits of trace ID
   * @param spanId 64-bit span ID
   * @return the index of the interned link
   */
  public int intern(long traceIdLow, long spanId) {
    if (traceIdLow == 0 && spanId == 0) {
      return 0;
    }

    int cached = linkToIndex.get(traceIdLow, spanId);
    if (cached != -1) return cached;

    // Cache miss — allocate byte arrays only once per unique link
    byte[] traceIdBytes = new byte[16];
    long tmp = traceIdLow;
    for (int i = 15; i >= 8; i--) {
      traceIdBytes[i] = (byte) (tmp & 0xFF);
      tmp >>>= 8;
    }

    byte[] spanIdBytes = new byte[8];
    tmp = spanId;
    for (int i = 7; i >= 0; i--) {
      spanIdBytes[i] = (byte) (tmp & 0xFF);
      tmp >>>= 8;
    }

    int index = links.size();
    links.add(new LinkEntry(traceIdBytes, spanIdBytes));
    linkToIndex.put(traceIdLow, spanId, index);
    return index;
  }

  private static long bytesToLong(byte[] bytes, int offset) {
    if (bytes.length < offset + 8) return 0;
    long v = 0;
    for (int i = offset; i < offset + 8; i++) {
      v = (v << 8) | (bytes[i] & 0xFF);
    }
    return v;
  }

  /**
   * Returns the link entry at the given index.
   *
   * @param index the index
   * @return the link entry
   * @throws IndexOutOfBoundsException if index is out of bounds
   */
  public LinkEntry get(int index) {
    return links.get(index);
  }

  /**
   * Returns the number of links (including the null link at index 0).
   *
   * @return the size of the link table
   */
  public int size() {
    return links.size();
  }

  /**
   * Returns the list of all link entries.
   *
   * @return the list of link entries
   */
  public List<LinkEntry> getLinks() {
    return links;
  }

  /** Resets the table to its initial state with only the null link at index 0. */
  public void reset() {
    links.clear();
    linkToIndex.clear();
    links.add(new LinkEntry(EMPTY_TRACE_ID, EMPTY_SPAN_ID));
  }
}
