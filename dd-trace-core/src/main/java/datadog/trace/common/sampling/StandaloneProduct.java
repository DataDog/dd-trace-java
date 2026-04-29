package datadog.trace.common.sampling;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.SamplingMechanism;

/**
 * Represents a standalone product that can function when APM tracing is disabled. Each product
 * defines which traces to keep, which sampling mechanism to report, and whether it requires a
 * 1-per-minute billing trace for service catalog / billing purposes.
 *
 * <p>To add a new standalone product:
 *
 * <ol>
 *   <li>Add an enum entry here.
 *   <li>Add one line in {@link Sampler.Builder#forConfig}.
 *   <li>Update {@code ProductTraceSource.STANDALONE_PRODUCTS_MASK} to include the new product's
 *       {@link ProductTraceSource} bit — otherwise {@code
 *       TraceCollector.setSamplingPriorityIfNecessary} will not recognize the product's traces.
 * </ol>
 */
public enum StandaloneProduct {

  /**
   * LLM Observability: keeps all LLMOBS-marked traces. No billing trace is needed because LLMObs
   * requires capturing every LLM interaction.
   */
  LLMOBS(ProductTraceSource.LLMOBS, SamplingMechanism.DEFAULT, false),

  /**
   * Application Security Management: keeps all ASM-marked traces and allows 1 APM trace per minute
   * so the service catalog and billing can detect the service as live.
   */
  ASM(ProductTraceSource.ASM, SamplingMechanism.APPSEC, true);

  /** The {@link ProductTraceSource} bit used to identify traces belonging to this product. */
  public final int traceSourceBit;

  /** The sampling mechanism to report when a trace is kept for this product. */
  public final byte samplingMechanism;

  /**
   * Whether this product requires a billing trace (1 APM trace per minute) even in the absence of
   * product-marked spans.
   */
  public final boolean needsBillingTrace;

  StandaloneProduct(int traceSourceBit, byte samplingMechanism, boolean needsBillingTrace) {
    this.traceSourceBit = traceSourceBit;
    this.samplingMechanism = samplingMechanism;
    this.needsBillingTrace = needsBillingTrace;
  }
}
