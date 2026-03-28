package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This sampler is used when APM tracing is disabled but LLM Observability is enabled. Unlike ASM
 * standalone mode which only needs 1 trace per minute for billing/catalog purposes, LLM
 * Observability needs to capture all LLM interactions to track costs, latency, and quality metrics.
 * Therefore, this sampler keeps all LLMOBS traces and drops all APM-only traces.
 */
public class LlmObsStandaloneSampler implements Sampler, PrioritySampler {

  private static final Logger log = LoggerFactory.getLogger(LlmObsStandaloneSampler.class);

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped.
    // This allows the core agent to collect stats on all traces.
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    // Only keep traces that have the LLMOBS product flag
    // Drop regular APM traces when APM tracing is disabled
    T rootSpan = span.getLocalRootSpan();
    if (rootSpan instanceof datadog.trace.core.DDSpan) {
      datadog.trace.core.DDSpan ddRootSpan = (datadog.trace.core.DDSpan) rootSpan;
      int traceSource = ddRootSpan.context().getPropagationTags().getTraceSource();
      if (ProductTraceSource.isProductMarked(traceSource, ProductTraceSource.LLMOBS)) {
        log.debug("Set SAMPLER_KEEP for LLMObs span {}", span.getSpanId());
        span.setSamplingPriority(SAMPLER_KEEP, SamplingMechanism.DEFAULT);
        return;
      }
    }
    // Drop APM-only traces when APM tracing is disabled
    log.debug("Set SAMPLER_DROP for APM-only span {}", span.getSpanId());
    span.setSamplingPriority(SAMPLER_DROP, SamplingMechanism.DEFAULT);
  }
}
