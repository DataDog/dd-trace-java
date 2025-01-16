package datadog.trace.common.sampling;

import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is designed to only allow 1 APM trace per minute for apm tracing disabled. The service
 * catalog and the billing need a continuous ingestion of at least at 1 trace per minute to consider
 * a service as being live and billable. In the absence of other products events, no APM traces must
 * be sent, so we need to let some regular APM traces go through.
 */
public class ApmTracingDisabledSampler implements Sampler, PrioritySampler {

  private static final Logger log = LoggerFactory.getLogger(ApmTracingDisabledSampler.class);
  private static final int RATE_IN_MILLISECONDS = 60000; // 1 minute

  private final AtomicLong lastSampleTime;
  private final Clock clock;

  public ApmTracingDisabledSampler(final Clock clock) {
    this.clock = clock;
    this.lastSampleTime = new AtomicLong(clock.millis() - RATE_IN_MILLISECONDS);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {

    if (shouldSample()) {
      log.debug("Set SAMPLER_KEEP for span {}", span.getSpanId());
      span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.APPSEC);
    } else {
      log.debug("Set SAMPLER_DROP for span {}", span.getSpanId());
      span.setSamplingPriority(PrioritySampling.SAMPLER_DROP, SamplingMechanism.APPSEC);
    }
  }

  private boolean shouldSample() {
    long now = clock.millis();
    return lastSampleTime.updateAndGet(
            lastTime -> now - lastTime >= RATE_IN_MILLISECONDS ? now : lastTime)
        == now;
  }
}
