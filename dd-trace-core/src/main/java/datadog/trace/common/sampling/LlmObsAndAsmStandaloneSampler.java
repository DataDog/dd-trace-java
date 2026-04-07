package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This sampler is used when APM tracing is disabled but both LLM Observability and ASM are enabled.
 * It keeps all LLMObs and ASM traces, and allows 1 APM trace per minute for billing/service catalog
 * purposes.
 */
public class LlmObsAndAsmStandaloneSampler implements Sampler, PrioritySampler {

  private static final Logger log = LoggerFactory.getLogger(LlmObsAndAsmStandaloneSampler.class);
  private static final int RATE_IN_MILLISECONDS = 60000; // 1 minute

  private final AtomicLong lastSampleTime;
  private final Clock clock;

  public LlmObsAndAsmStandaloneSampler(final Clock clock) {
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
    T rootSpan = span.getLocalRootSpan();
    if (rootSpan instanceof DDSpan) {
      DDSpan ddRootSpan = (DDSpan) rootSpan;
      int traceSource = ddRootSpan.context().getPropagationTags().getTraceSource();
      if (ProductTraceSource.isProductMarked(traceSource, ProductTraceSource.LLMOBS)) {
        log.debug("Set SAMPLER_KEEP for LLMObs span {}", span.getSpanId());
        span.setSamplingPriority(SAMPLER_KEEP, SamplingMechanism.DEFAULT);
        return;
      }
      if (ProductTraceSource.isProductMarked(traceSource, ProductTraceSource.ASM)) {
        log.debug("Set SAMPLER_KEEP for ASM span {}", span.getSpanId());
        span.setSamplingPriority(SAMPLER_KEEP, SamplingMechanism.APPSEC);
        return;
      }
    }
    // For APM-only traces, allow 1 per minute for billing/catalog purposes
    if (shouldSample()) {
      log.debug("Set SAMPLER_KEEP for APM span {}", span.getSpanId());
      span.setSamplingPriority(SAMPLER_KEEP, SamplingMechanism.APPSEC);
    } else {
      log.debug("Set SAMPLER_DROP for APM span {}", span.getSpanId());
      span.setSamplingPriority(SAMPLER_DROP, SamplingMechanism.APPSEC);
    }
  }

  private boolean shouldSample() {
    long now = clock.millis();
    return lastSampleTime.updateAndGet(
            lastTime -> now - lastTime >= RATE_IN_MILLISECONDS ? now : lastTime)
        == now;
  }
}
