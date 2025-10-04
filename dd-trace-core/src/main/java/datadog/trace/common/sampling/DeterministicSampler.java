package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;

/**
 * This implements the deterministic sampling algorithm used by the Datadog Agent as well as the
 * tracers for other languages
 */
public abstract class DeterministicSampler implements RateSampler {

  /** Uses trace-id as a sampling id */
  public static final class TraceSampler extends DeterministicSampler {

    public TraceSampler(double rate) {
      super(rate);
    }

    @Override
    protected <T extends CoreSpan<T>> long getSamplingId(T span) {
      return span.getTraceId().toLong();
    }
  }

  /** Uses span-id as a sampling id */
  public static final class SpanSampler extends DeterministicSampler {

    public SpanSampler(double rate) {
      super(rate);
    }

    @Override
    protected <T extends CoreSpan<T>> long getSamplingId(T span) {
      return span.getSpanId();
    }
  }

  private static final long KNUTH_FACTOR = 1111111111111111111L;

  private static final double MAX = Math.pow(2, 64) - 1;

  private final float rate;
  private final long threshold;
  private final String knuthSampleRate;

  public DeterministicSampler(final double rate) {
    this.rate = (float) rate;
    this.threshold = cutoff(rate);
    this.knuthSampleRate = formatKnuthSamplingRate(rate);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // unsigned 64 bit comparison with cutoff/threshold
    return getSamplingId(span) * KNUTH_FACTOR + Long.MIN_VALUE <= threshold;
  }

  protected abstract <T extends CoreSpan<T>> long getSamplingId(T span);

  @Override
  public double getSampleRate() {
    return rate;
  }

  @Override
  public String getKnuthSampleRate() {
    return knuthSampleRate;
  }

  public static long cutoff(double rate) {
    if (rate < 0.5) {
      return (long) (rate * MAX) + Long.MIN_VALUE;
    }
    if (rate < 1.0) {
      return (long) ((rate * MAX) + Long.MIN_VALUE);
    }
    return Long.MAX_VALUE;
  }

  /**
   * Thread-safe custom formatter for Knuth sampling rates. Formats rates to up to 6 decimal places,
   * removing trailing zeros. Assumes the value is between 0 and 1 (inclusive).
   */
  private static String formatKnuthSamplingRate(double value) {
    if (value <= 0) {
      return "0";
    } else if (value >= 1) {
      return "1";
    } else {
      // Scale to 6 decimal places and round
      long scaled = Math.round(value * 1_000_000);

      // Handle rounding to 1.0 case
      if (scaled >= 1_000_000) {
        return "1";
      }

      // Convert back to string with proper decimal formatting
      String result = "0." + String.format("%06d", scaled);

      // Remove trailing zeros
      int endIndex = result.length();
      while (endIndex > 2 && result.charAt(endIndex - 1) == '0') {
        endIndex--;
      }

      return result.substring(0, endIndex);
    }
  }
}
