package datadog.trace.common.sampling;

import datadog.trace.api.DDId;
import datadog.trace.core.DDSpan;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;

/**
 * This implements the deterministic sampling algorithm used by the Datadog Agent as well as the
 * tracers for other languages
 */
@Slf4j
public class DeterministicSampler implements RateSampler {
  private static final long KNUTH_FACTOR = 1111111111111111111L;

  private final long cutoff; // pre-calculated for the unsigned 64 bit comparison
  private final double rate;

  public DeterministicSampler(final double rate) {
    this.rate = rate;
    cutoff =
        new BigDecimal(rate).multiply(new BigDecimal(DDId.TRACE_ID_MAX)).toBigInteger().longValue()
            + Long.MIN_VALUE;

    log.debug("Initializing the RateSampler, sampleRate: {} %", rate * 100);
  }

  @Override
  public boolean sample(final DDSpan span) {
    boolean sampled = false;
    if (rate >= 1) {
      sampled = true;
    } else if (rate > 0) {
      long mod = span.getTraceId().toLong() * KNUTH_FACTOR;
      // unsigned 64 bit comparison with pre-calculated cutoff
      if (mod + Long.MIN_VALUE < cutoff) {
        sampled = true;
      }
    }

    log.debug("{} - Span is sampled: {}", span, sampled);

    return sampled;
  }

  @Override
  public double getSampleRate() {
    return rate;
  }
}
