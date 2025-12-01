package com.datadog.profiling.otel.dictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Link deduplication table for OTLP profiles. Index 0 is reserved for the null/unset link. A link
 * connects a profile sample to a trace span for correlation.
 */
public final class LinkTable {

  /** Wrapper for trace/span ID pair to use as HashMap key. */
  private static final class LinkKey {
    final byte[] traceId;
    final byte[] spanId;
    private final int hashCode;

    LinkKey(byte[] traceId, byte[] spanId) {
      this.traceId = traceId;
      this.spanId = spanId;
      this.hashCode = 31 * Arrays.hashCode(traceId) + Arrays.hashCode(spanId);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LinkKey that = (LinkKey) o;
      return Arrays.equals(traceId, that.traceId) && Arrays.equals(spanId, that.spanId);
    }

    @Override
    public int hashCode() {
      return hashCode;
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
  private final Map<LinkKey, Integer> linkToIndex;

  public LinkTable() {
    links = new ArrayList<>();
    linkToIndex = new HashMap<>();
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
    if (isAllZeros(traceId) && isAllZeros(spanId)) {
      return 0;
    }

    LinkKey key = new LinkKey(traceId, spanId);
    Integer existing = linkToIndex.get(key);
    if (existing != null) {
      return existing;
    }

    int index = links.size();
    // Make defensive copies
    byte[] traceIdCopy = Arrays.copyOf(traceId, traceId.length);
    byte[] spanIdCopy = Arrays.copyOf(spanId, spanId.length);
    links.add(new LinkEntry(traceIdCopy, spanIdCopy));
    linkToIndex.put(new LinkKey(traceIdCopy, spanIdCopy), index);
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

    byte[] traceIdBytes = new byte[16];
    // Put trace ID in lower 64 bits (big-endian)
    for (int i = 15; i >= 8; i--) {
      traceIdBytes[i] = (byte) (traceIdLow & 0xFF);
      traceIdLow >>>= 8;
    }

    byte[] spanIdBytes = new byte[8];
    for (int i = 7; i >= 0; i--) {
      spanIdBytes[i] = (byte) (spanId & 0xFF);
      spanId >>>= 8;
    }

    return intern(traceIdBytes, spanIdBytes);
  }

  private static boolean isAllZeros(byte[] bytes) {
    for (byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
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
