package datadog.trace.core.datastreams;

import datadog.trace.api.DDId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modelled after DeterministicSampler, this implements deterministic sampling to decide whether pathways for
 * Datastreams will be injected into Kafka headers (or other carriers).
 */
public class InjectionSampler {
  private static final Logger log = LoggerFactory.getLogger(InjectionSampler.class);

  public static final InjectionSampler INJECTION_SAMPLER = new InjectionSampler(0.1);

  private static final long KNUTH_FACTOR = 1111111111111111111L;

  private static final double MAX = Math.pow(2, 64) - 1;

  private final float rate;

  public InjectionSampler(final double rate) {
    this.rate = (float) rate;
  }

  public boolean sample(DDId spanId) {
    // unsigned 64 bit comparison with cutoff
    boolean keep = spanId.toLong() * KNUTH_FACTOR + Long.MIN_VALUE < cutoff(rate);
    log.info("[HKT113] sampling at rate of " + rate + ": keep? " + keep);
    return keep;
  }

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
