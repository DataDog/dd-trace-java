package com.datadog.featureflag;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class FlagEvaluationAggregator {

  static final int MAX_CONTEXT_FIELDS = 256;
  static final int MAX_FIELD_LENGTH = 256;

  private static final byte CTX_TAG_STRING = 's';
  private static final byte CTX_TAG_BOOL = 'b';
  private static final byte CTX_TAG_INT = 'i';
  private static final byte CTX_TAG_LONG = 'l';
  private static final byte CTX_TAG_FLOAT = 'f';
  private static final byte CTX_TAG_DOUBLE = 'd';
  private static final byte CTX_TAG_OTHER = 'o';

  private FlagEvaluationAggregator() {}

  static Map<String, Object> pruneContext(final Map<String, Object> attrs) {
    if (attrs == null || attrs.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    final TreeMap<String, Object> out = new TreeMap<>();
    final TreeMap<String, Object> sorted = new TreeMap<>(attrs);
    int count = 0;
    for (final Map.Entry<String, Object> entry : sorted.entrySet()) {
      if (count >= MAX_CONTEXT_FIELDS) {
        break;
      }
      final Object v = entry.getValue();
      if (v instanceof String && ((String) v).length() > MAX_FIELD_LENGTH) {
        continue;
      }
      out.put(entry.getKey(), v);
      count++;
    }
    return out;
  }

  static String canonicalContextKey(final Map<String, Object> prunedAttrs) {
    if (prunedAttrs == null || prunedAttrs.isEmpty()) {
      return "";
    }
    final Map<String, Object> sorted =
        (prunedAttrs instanceof TreeMap) ? prunedAttrs : new TreeMap<>(prunedAttrs);
    final StringBuilder sb = new StringBuilder();
    for (final Map.Entry<String, Object> entry : sorted.entrySet()) {
      appendLengthDelimited(sb, entry.getKey());
      appendContextValue(sb, entry.getValue());
    }
    return sb.toString();
  }

  private static void appendLengthDelimited(final StringBuilder sb, final String s) {
    sb.append(String.format("%08x", (long) s.length()));
    sb.append(s);
  }

  private static void appendContextValue(final StringBuilder sb, final Object v) {
    if (v instanceof Boolean) {
      sb.append((char) CTX_TAG_BOOL);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Long) {
      sb.append((char) CTX_TAG_LONG);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Integer) {
      sb.append((char) CTX_TAG_INT);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Float) {
      sb.append((char) CTX_TAG_FLOAT);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof Double) {
      sb.append((char) CTX_TAG_DOUBLE);
      appendLengthDelimited(sb, v.toString());
    } else if (v instanceof String) {
      sb.append((char) CTX_TAG_STRING);
      appendLengthDelimited(sb, (String) v);
    } else {
      sb.append((char) CTX_TAG_OTHER);
      appendLengthDelimited(sb, v == null ? "" : v.toString());
    }
  }

  static class EvalBucket {
    long count;
    long firstEvalMs;
    long lastEvalMs;
    boolean runtimeDefaultUsed;
    String flagKey;
    String variant;
    String allocationKey;
    String targetingKey;
    String errorMessage;
    Map<String, Object> prunedAttrs;

    EvalBucket(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final String targetingKey,
        final String errorMessage,
        final long evalTimeMs,
        final boolean runtimeDefaultUsed,
        final Map<String, Object> prunedAttrs) {
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.targetingKey = targetingKey;
      this.errorMessage = errorMessage;
      this.firstEvalMs = evalTimeMs;
      this.lastEvalMs = evalTimeMs;
      this.count = 1;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
      this.prunedAttrs = prunedAttrs;
    }

    int prunedContextFieldCount() {
      return prunedAttrs == null ? 0 : prunedAttrs.size();
    }

    void merge(final long evalTimeMs, final boolean isDefault) {
      count++;
      if (evalTimeMs < firstEvalMs) {
        firstEvalMs = evalTimeMs;
      }
      if (evalTimeMs > lastEvalMs) {
        lastEvalMs = evalTimeMs;
      }
      if (isDefault) {
        runtimeDefaultUsed = true;
      }
    }
  }

  static final class FullKey {
    private final String flagKey;
    private final String variant;
    private final String allocationKey;
    private final boolean runtimeDefaultUsed;
    private final String errorMessage;
    private final String targetingKey;
    private final String contextKey;

    FullKey(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final boolean runtimeDefaultUsed,
        final String errorMessage,
        final String targetingKey,
        final String contextKey) {
      this.flagKey = flagKey;
      this.variant = variant;
      this.allocationKey = allocationKey;
      this.runtimeDefaultUsed = runtimeDefaultUsed;
      this.errorMessage = errorMessage;
      this.targetingKey = targetingKey;
      this.contextKey = contextKey;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FullKey)) {
        return false;
      }
      final FullKey fullKey = (FullKey) o;
      return runtimeDefaultUsed == fullKey.runtimeDefaultUsed
          && Objects.equals(flagKey, fullKey.flagKey)
          && Objects.equals(variant, fullKey.variant)
          && Objects.equals(allocationKey, fullKey.allocationKey)
          && Objects.equals(errorMessage, fullKey.errorMessage)
          && Objects.equals(targetingKey, fullKey.targetingKey)
          && Objects.equals(contextKey, fullKey.contextKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          flagKey,
          variant,
          allocationKey,
          runtimeDefaultUsed,
          errorMessage,
          targetingKey,
          contextKey);
    }
  }
}
