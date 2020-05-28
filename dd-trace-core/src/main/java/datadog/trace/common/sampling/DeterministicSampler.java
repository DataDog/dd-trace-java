package datadog.trace.common.sampling;

import datadog.trace.core.CoreTracer;
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

  private final long cutoff;
  private final double rate;

  public DeterministicSampler(final double rate) {
    this.rate = rate;
    cutoff =
        new BigDecimal(rate)
            .multiply(new BigDecimal(CoreTracer.TRACE_ID_MAX))
            .toBigInteger()
            .longValue();

    log.debug("Initializing the RateSampler, sampleRate: {} %", rate * 100);
  }

  @Override
  public boolean sample(final DDSpan span) {
    final boolean sampled;
    if (rate == 1) {
      sampled = true;
    } else if (rate == 0) {
      sampled = false;
    } else {
      long mod = span.getTraceId().toLong() * KNUTH_FACTOR;
      if (cutoff >= 0) {
        sampled = mod >= 0 && mod < cutoff;
      } else {
        sampled = mod < 0 && mod >= cutoff;
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
