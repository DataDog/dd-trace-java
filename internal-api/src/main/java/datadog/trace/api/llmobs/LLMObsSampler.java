package datadog.trace.api.llmobs;

import datadog.trace.api.Config;

/**
 * Head-based retention sampler for LLM Observability traces.
 *
 * <p>Duplicates the arithmetic in {@code DeterministicSampler} because neither of those modules
 * depends on {@code dd-trace-core}; {@code ApiSecurityDownstreamSamplerImpl} does the same.
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
    // Any rate outside [0.0, 1.0] (including NaN) falls back to 1.0
    this.rate = (rate >= 0.0 && rate <= 1.0) ? rate : 1.0;
    this.threshold = cutoff(this.rate);
    this.formattedRate = formatRate(this.rate);
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
   * Formats a sampling rate with up to 6 decimal digits of precision and no trailing zeros. Mirrors
   * {@code format_rate} in dd-trace-py, which stamps {@code sample_rate} through the same helper it
   * uses for {@code _dd.p.ksr}, so the two languages report the same rate as the same string.
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
