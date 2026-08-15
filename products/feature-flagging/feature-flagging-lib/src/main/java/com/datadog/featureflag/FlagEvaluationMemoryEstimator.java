package com.datadog.featureflag;

import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import java.util.Map;

/** Conservatively estimates memory retained by one aggregation bucket. */
final class FlagEvaluationMemoryEstimator {

  // These constants include aligned object headers, references, and amortized HashMap storage.
  // They intentionally overestimate JOL measurements made with 100-bucket representative graphs.
  private static final long BUCKET_AND_INDEX_BYTES = 256;
  private static final long CONTEXT_MAP_BYTES = 64;
  private static final long CONTEXT_ENTRY_BYTES = 48;
  private static final long STRING_BYTES = 40;
  private static final long OTHER_VALUE_BYTES = 64;
  private static final long MAX_BYTES_PER_CHARACTER = 2;

  private FlagEvaluationMemoryEstimator() {}

  static long fullBucketBytes(
      final FlagEvalEvent event,
      final Map<String, Object> prunedAttrs,
      final String canonicalContextKey) {
    long bytes = BUCKET_AND_INDEX_BYTES;
    bytes = add(bytes, stringBytes(event.flagKey));
    bytes = add(bytes, stringBytes(event.variant));
    bytes = add(bytes, stringBytes(event.allocationKey));
    bytes = add(bytes, stringBytes(event.targetingKey));
    bytes = add(bytes, stringBytes(event.errorMessage));
    bytes = add(bytes, stringBytes(canonicalContextKey));
    if (prunedAttrs == null || prunedAttrs.isEmpty()) {
      return bytes;
    }

    bytes = add(bytes, CONTEXT_MAP_BYTES);
    for (final Map.Entry<String, Object> entry : prunedAttrs.entrySet()) {
      bytes = add(bytes, CONTEXT_ENTRY_BYTES);
      bytes = add(bytes, stringBytes(entry.getKey()));
      bytes = add(bytes, contextValueBytes(entry.getValue()));
    }
    return bytes;
  }

  static long degradedBucketBytes(final FlagEvalEvent event) {
    long bytes = BUCKET_AND_INDEX_BYTES;
    bytes = add(bytes, stringBytes(event.flagKey));
    bytes = add(bytes, stringBytes(event.variant));
    bytes = add(bytes, stringBytes(event.allocationKey));
    return add(bytes, stringBytes(event.errorMessage));
  }

  private static long contextValueBytes(final Object value) {
    if (value instanceof String) {
      return stringBytes((String) value);
    }
    if (value == null) {
      return 0;
    }
    return add(OTHER_VALUE_BYTES, characterBytes(value.toString().length()));
  }

  private static long stringBytes(final String value) {
    return value == null ? 0 : add(STRING_BYTES, characterBytes(value.length()));
  }

  private static long characterBytes(final int length) {
    return MAX_BYTES_PER_CHARACTER * length;
  }

  private static long add(final long left, final long right) {
    // Both inputs describe live Java objects. Their sum cannot approach the long range in one JVM.
    return left + right;
  }
}
