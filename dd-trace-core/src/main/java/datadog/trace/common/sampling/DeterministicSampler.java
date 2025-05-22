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

  public DeterministicSampler(final double rate) {
    this.rate = (float) rate;
    this.threshold = cutoff(rate);
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

  public static long cutoff(double rate) {
    if (rate < 0.5) {
      return (long) (rate * MAX) + Long.MIN_VALUE;
    }
    if (rate < 1.0) {
      return (long) ((rate * MAX) + Long.MIN_VALUE);
    }
    return Long.MAX_VALUE;
  }
}
