package com.datadog.profiling.otel.proto.dictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Link deduplication table for OTLP profiles. Index 0 is reserved for the null/unset link. A link
 * connects a profile sample to a trace span for correlation.
 */
public final class LinkTable {

  /** Open-addressing map keyed on three longs (traceIdHigh, traceIdLow, spanId). */
  private static final class LongLongToIntMap {
    private long[] keys1;
    private long[] keys2;
    private long[] keys3;
    private int[] values;
    private byte[] used;
    private int mask;
    private int size;

    LongLongToIntMap(int initialCapacity) {
      int cap = Integer.highestOneBit(Math.max(initialCapacity * 2, 16) - 1) << 1;
      keys1 = new long[cap];
      keys2 = new long[cap];
      keys3 = new long[cap];
      values = new int[cap];
      used = new byte[cap];
      mask = cap - 1;
    }

    int get(long k1, long k2, long k3) {
      int slot = (int) (mix(mix(k1) ^ k2) ^ mix(k3)) & mask;
      while (used[slot] != 0) {
        if (keys1[slot] == k1 && keys2[slot] == k2 && keys3[slot] == k3) return values[slot];
        slot = (slot + 1) & mask;
      }
      return -1;
    }

    void put(long k1, long k2, long k3, int value) {
      if (size * 2 >= mask) resize();
      int slot = (int) (mix(mix(k1) ^ k2) ^ mix(k3)) & mask;
      while (used[slot] != 0) {
        if (keys1[slot] == k1 && keys2[slot] == k2 && keys3[slot] == k3) {
          values[slot] = value;
          return;
        }
        slot = (slot + 1) & mask;
      }
      used[slot] = 1;
      keys1[slot] = k1;
      keys2[slot] = k2;
      keys3[slot] = k3;
      values[slot] = value;
      size++;
    }

    void clear() {
      Arrays.fill(used, (byte) 0);
      size = 0;
    }

    private void resize() {
      long[] oldKeys1 = keys1;
      long[] oldKeys2 = keys2;
      long[] oldKeys3 = keys3;
      int[] oldValues = values;
      byte[] oldUsed = used;
      int newCap = (mask + 1) * 2;
      keys1 = new long[newCap];
      keys2 = new long[newCap];
      keys3 = new long[newCap];
      values = new int[newCap];
      used = new byte[newCap];
      mask = newCap - 1;
      size = 0;
      for (int i = 0; i < oldKeys1.length; i++) {
        if (oldUsed[i] != 0) put(oldKeys1[i], oldKeys2[i], oldKeys3[i], oldValues[i]);
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

  public int intern(byte[] traceId, byte[] spanId) {
    if (traceId == null || spanId == null) {
      return 0;
    }
    // Key on the full 128-bit trace id plus the span id so distinct trace ids sharing the
    // low 64 bits never alias to the same link
    long traceIdHigh = bytesToLong(traceId, 0);
    long traceIdLow = bytesToLong(traceId, 8);
    long spanIdLong = bytesToLong(spanId, 0);
    if (traceIdHigh == 0 && traceIdLow == 0 && spanIdLong == 0) {
      return 0;
    }

    int cached = linkToIndex.get(traceIdHigh, traceIdLow, spanIdLong);
    if (cached != -1) return cached;

    int index = links.size();
    byte[] traceIdCopy = Arrays.copyOf(traceId, traceId.length);
    byte[] spanIdCopy = Arrays.copyOf(spanId, spanId.length);
    links.add(new LinkEntry(traceIdCopy, spanIdCopy));
    linkToIndex.put(traceIdHigh, traceIdLow, spanIdLong, index);
    return index;
  }

  public int intern(long traceIdLow, long spanId) {
    if (traceIdLow == 0 && spanId == 0) {
      return 0;
    }

    // the long overload only carries the low 64 bits of the trace id; the high bits key as 0
    int cached = linkToIndex.get(0, traceIdLow, spanId);
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
    linkToIndex.put(0, traceIdLow, spanId, index);
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

  public LinkEntry get(int index) {
    return links.get(index);
  }

  public int size() {
    return links.size();
  }

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
