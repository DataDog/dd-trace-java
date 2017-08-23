package com.datadoghq.trace.sampling;

import com.datadoghq.trace.DDBaseSpan;
import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;

/**
 * This sampler sample the traces at a predefined rate.
 *
 * <p>Keep (100 * `sample_rate`)% of the traces. It samples randomly, its main purpose is to reduce
 * the integration footprint.
 */
@Slf4j
@AutoService(Sampler.class)
public class RandomSampler extends AbstractSampler {

  /** The sample rate used */
  private final double sampleRate;

  /**
   * Build an instance of the sampler. The Sample rate is fixed for each instance.
   *
   * @param sampleRate a number [0,1] representing the rate ratio.
   */
  public RandomSampler(double sampleRate) {

    if (sampleRate <= 0) {
      sampleRate = 1;
      log.error("SampleRate is negative or null, disabling the sampler");
    } else if (sampleRate > 1) {
      sampleRate = 1;
    }

    this.sampleRate = sampleRate;
    log.debug("Initializing the RandomSampler, sampleRate: {} %", this.sampleRate * 100);
  }

  @Override
  public boolean doSample(final DDBaseSpan<?> span) {
    final boolean sample = Math.random() <= this.sampleRate;
    log.debug("{} - Span is sampled: {}", span, sample);
    return sample;
  }

  public double getSampleRate() {
    return this.sampleRate;
  }
}
