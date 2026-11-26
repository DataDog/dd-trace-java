package datadog.trace.core.datastreams;

import static datadog.trace.api.DDTags.PATHWAY_HASH;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Sets {@code pathway.hash} on a span, reusing the decimal string for recent hashes. */
final class PathwayHashTag {
  private static final int SIZE = 256; // power of two
  private static final Cached[] STRINGS = new Cached[SIZE];

  private PathwayHashTag() {}

  /**
   * @param sampledOnly skip spans whose trace is already known to be dropped; a span with no
   *     sampling decision yet is still tagged
   */
  static void set(AgentSpan span, long pathwayHash, boolean sampledOnly) {
    if (pathwayHash == 0) {
      return;
    }
    if (sampledOnly) {
      int priority = span.spanContext().getSamplingPriority();
      if (priority != PrioritySampling.UNSET && priority <= 0) {
        return;
      }
    }
    span.setTag(PATHWAY_HASH, toUnsignedString(pathwayHash));
  }

  static String toUnsignedString(long pathwayHash) {
    int index = (int) (pathwayHash ^ (pathwayHash >>> 32)) & (SIZE - 1);
    Cached cached = STRINGS[index];
    if (cached != null && cached.hash == pathwayHash) {
      return cached.value;
    }
    String value = Long.toUnsignedString(pathwayHash);
    STRINGS[index] = new Cached(pathwayHash, value);
    return value;
  }

  private static final class Cached {
    final long hash;
    final String value;

    Cached(long hash, String value) {
      this.hash = hash;
      this.value = value;
    }
  }
}
