package com.datadog.profiling.otel.proto.dictionary;

import com.datadog.profiling.otel.LongKeyIntMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Link deduplication table for OTLP profiles. Index 0 is reserved for the null/unset link. A link
 * connects a profile sample to a trace span for correlation.
 */
public final class LinkTable {

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
  private final LongKeyIntMap linkToIndex;

  public LinkTable() {
    links = new ArrayList<>();
    linkToIndex = new LongKeyIntMap(3, 16);
    // Index 0 is reserved for null/unset link
    links.add(new LinkEntry(EMPTY_TRACE_ID, EMPTY_SPAN_ID));
  }

  /**
   * Interns a link keyed on the full 128-bit trace id plus the span id. No production call site yet
   * — the converter only carries spanId/localRootSpanId longs; this overload exists for when the
   * profiling JFR events gain a real 128-bit trace id.
   *
   * <p>The key spaces of the two overloads are kept disjoint (the long overload always keys
   * traceIdHigh as 0; this overload tags the high bit) so equivalent links cannot alias across
   * overloads.
   */
  public int intern(byte[] traceId, byte[] spanId) {
    if (traceId == null || spanId == null) {
      return 0;
    }
    long traceIdHigh = bytesToLong(traceId, 0);
    long traceIdLow = bytesToLong(traceId, 8);
    long spanIdLong = bytesToLong(spanId, 0);
    if (traceIdHigh == 0 && traceIdLow == 0 && spanIdLong == 0) {
      return 0;
    }

    // tag the high bit so byte[]-overload keys never collide with long-overload keys (which
    // always carry traceIdHigh == 0)
    long keyHigh = traceIdHigh | Long.MIN_VALUE;
    int cached = linkToIndex.get3(keyHigh, traceIdLow, spanIdLong);
    if (cached != -1) return cached;

    int index = links.size();
    byte[] traceIdCopy = Arrays.copyOf(traceId, traceId.length);
    byte[] spanIdCopy = Arrays.copyOf(spanId, spanId.length);
    links.add(new LinkEntry(traceIdCopy, spanIdCopy));
    linkToIndex.put3(keyHigh, traceIdLow, spanIdLong, index);
    return index;
  }

  /**
   * Interns a link keyed on the low 64 bits of the trace id plus the span id — the only overload
   * with a production call site (see JfrToOtlpConverter.extractLinkIndex).
   */
  public int intern(long traceIdLow, long spanId) {
    if (traceIdLow == 0 && spanId == 0) {
      return 0;
    }

    // the long overload only carries the low 64 bits of the trace id; the high bits key as 0
    // (byte[]-overload keys tag the high bit, keeping the two key spaces disjoint)
    int cached = linkToIndex.get3(0, traceIdLow, spanId);
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
    linkToIndex.put3(0, traceIdLow, spanId, index);
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
