package datadog.trace.llmobs.domain;

import datadog.trace.api.Config;

/**
 * Head-based retention sampler for LLM Observability traces.
 *
 * <p>The decision is deterministic in the APM trace ID, using the same algorithm as the Datadog
 * Agent and the other tracer libraries. Two consequences matter:
 *
 * <ul>
 *   <li>An LLMObs trace and its APM trace are retained or dropped together as often as the two
 *       rates allow, which keeps the two products' views of the same request consistent.
 *   <li>Services that share a sample rate reach the same decision for the same trace without
 *       exchanging anything, so a distributed trace is retained or dropped as a whole even though
 *       dd-trace-java does not yet propagate LLMObs sampling state across process boundaries.
 * </ul>
 *
 * <p>This sampler never drops a span locally. Its decision is reported to the intake, which
 * performs the drop, so that token and cost metrics are computed over 100% of instrumented traces
 * regardless of the configured rate.
 *
 * <p>Duplicates the arithmetic in {@code DeterministicSampler} because {@code agent-llmobs} does
 * not depend on {@code dd-trace-core}; {@code ApiSecurityDownstreamSamplerImpl} does the same.
 * {@link LLMObsSamplerTest} pins the shared constants and a set of decision vectors so the copies
 * cannot drift apart silently.
 */
public final class LLMObsSampler {

  private static final long KNUTH_FACTOR = 1111111111111111111L;
  private static final double MAX = Math.pow(2, 64) - 1;

  private final double rate;
  private final long threshold;
  private final String formattedRate;

  public static LLMObsSampler fromConfig() {
    return new LLMObsSampler(Config.get().getLlmObsSampleRate());
  }

  public LLMObsSampler(final double rate) {
    // NaN is clamped to 1.0 rather than left alone: every comparison against it is false, which
    // would otherwise leave the cutoff and the reported rate disagreeing about what happened.
    final double bounded = Double.isNaN(rate) ? 1.0 : rate;
    this.rate = bounded < 0.0 ? 0.0 : (bounded > 1.0 ? 1.0 : bounded);
    this.threshold = cutoff(this.rate);
    this.formattedRate = formatRate(this.rate);
  }

  /**
   * Whether a rate below 1.0 is configured. At 1.0 every trace is retained, so callers skip
   * stamping sampling fields altogether and the emitted payload is unchanged from an unconfigured
   * tracer.
   */
  public boolean isConfigured() {
    return rate < 1.0;
  }

  /**
   * The configured rate, formatted for the wire. Derived from the configured {@code double} rather
   * than from a narrowed {@code float}, so the reported rate is the rate actually applied.
   */
  public String formattedRate() {
    return formattedRate;
  }

  /**
   * @param samplingId the low-order 64 bits of the APM trace ID.
   * @return whether the trace is retained.
   */
  public boolean sample(final long samplingId) {
    // unsigned 64 bit comparison with cutoff/threshold
    return samplingId * KNUTH_FACTOR + Long.MIN_VALUE <= threshold;
  }

  private static long cutoff(final double rate) {
    if (rate < 0.5) {
      return (long) (rate * MAX) + Long.MIN_VALUE;
    }
    if (rate < 1.0) {
      return (long) ((rate * MAX) + Long.MIN_VALUE);
    }
    return Long.MAX_VALUE;
  }

  /**
   * Formats a sampling rate with up to 6 decimal digits of precision and no trailing zeros,
   * matching the format the tracer already propagates for {@code _dd.p.ksr}. Keeping the two
   * identical means the rate reported today and the rate propagated once distributed support lands
   * cannot disagree.
   */
  static String formatRate(final double rate) {
    if (rate <= 0.0) {
      return "0";
    }
    if (rate >= 1.0) {
      return "1";
    }
    long rounded = Math.round(rate * 1_000_000L);
    if (rounded <= 0) {
      return "0";
    }
    if (rounded >= 1_000_000L) {
      return "1";
    }
    // Build "0.DDDDDD", then trim trailing zeros.
    char[] chars = new char[8];
    chars[0] = '0';
    chars[1] = '.';
    for (int i = 7; i >= 2; i--) {
      chars[i] = (char) ('0' + (rounded % 10));
      rounded /= 10;
    }
    int end = 8;
    while (chars[end - 1] == '0') {
      end--;
    }
    return new String(chars, 0, end);
  }
}
