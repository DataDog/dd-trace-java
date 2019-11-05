package datadog.trace.common.sampling;

import datadog.opentracing.DDSpan;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RandomSampler implements RateSampler {
  private final double rate;

  public RandomSampler(final double rate) {
    this.rate = rate;
    log.debug("Initializing the RateSampler, sampleRate: {} %", rate * 100);
  }

  @Override
  public boolean sample(final DDSpan span) {
    final boolean sample = ThreadLocalRandom.current().nextFloat() <= rate;
    log.debug("{} - Span is sampled: {}", span, sample);
    return sample;
  }

  @Override
  public double getSampleRate() {
    return rate;
  }
}
