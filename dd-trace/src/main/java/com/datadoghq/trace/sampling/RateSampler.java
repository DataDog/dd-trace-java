package com.datadoghq.trace.sampling;

import com.datadoghq.trace.DDBaseSpan;
import com.google.auto.service.AutoService;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;

/**
 * This sampler sample the traces at a predefined rate.
 *
 * <p>Keep (100 * `sample_rate`)% of the traces. It samples randomly, its main purpose is to reduce
 * the integration footprint.
 */
@Slf4j
@AutoService(Sampler.class)
public class RateSampler extends AbstractSampler {
  private static final double DEFAULT_RATE = 1_000;

  /** The sample rate used */
  private final RateLimiter limiter;

  /**
   * Build an instance of the sampler. The Sample rate is fixed for each instance.
   *
   * @param sampleRatePerSecond a number [0,1] representing the rate ratio.
   */
  public RateSampler(double sampleRatePerSecond) {

    if (sampleRatePerSecond <= 0) {
      sampleRatePerSecond = DEFAULT_RATE;
      log.error(
          "RateSampler rate is negative or zero, using the default of {} samples per second",
          DEFAULT_RATE);
    }

    limiter = RateLimiter.create(sampleRatePerSecond);
    log.debug("Initializing the RateSampler, sampleRate: {} per second", sampleRatePerSecond);
  }

  @Override
  public boolean doSample(final DDBaseSpan<?> span) {
    final boolean sample = limiter.tryAcquire();
    log.debug("{} - Span is sampled: {}", span, sample);
    return sample;
  }
}
