package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A unified sampler for when APM tracing is disabled but one or more standalone products are
 * active.
 *
 * <p>For each span, the sampler checks whether the root span's {@link ProductTraceSource} bitfield
 * matches any of the active products (in list order). The first match wins: the trace is kept with
 * that product's sampling mechanism.
 *
 * <p>For APM-only traces (no product match):
 *
 * <ul>
 *   <li>If any active product requires a billing trace ({@link StandaloneProduct#needsBillingTrace}
 *       ), one APM trace per minute is allowed through with {@link SamplingMechanism#APPSEC}.
 *   <li>Otherwise all APM-only traces are dropped with {@link SamplingMechanism#DEFAULT}.
 * </ul>
 */
public class StandaloneSampler implements Sampler, PrioritySampler {

  private static final Logger log = LoggerFactory.getLogger(StandaloneSampler.class);
  private static final int RATE_IN_MILLISECONDS = 60000; // 1 minute

  private final List<StandaloneProduct> activeProducts;
  private final boolean needsBillingTrace;
  private final byte billingMechanism;
  private final AtomicLong lastSampleTime;
  private final Clock clock;

  public StandaloneSampler(final List<StandaloneProduct> activeProducts, final Clock clock) {
    this.activeProducts = activeProducts;
    this.clock = clock;
    this.needsBillingTrace = activeProducts.stream().anyMatch(p -> p.needsBillingTrace);
    this.billingMechanism =
        activeProducts.stream()
            .filter(p -> p.needsBillingTrace)
            .map(p -> p.samplingMechanism)
            .findFirst()
            .orElse(SamplingMechanism.DEFAULT);
    this.lastSampleTime = new AtomicLong(clock.millis() - RATE_IN_MILLISECONDS);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Priority sampling sends all traces to the core agent, including traces marked dropped,
    // so the agent can collect stats on all traces.
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    T rootSpan = span.getLocalRootSpan();
    if (rootSpan instanceof DDSpan) {
      DDSpan ddRootSpan = (DDSpan) rootSpan;
      int traceSource = ddRootSpan.context().getPropagationTags().getTraceSource();
      for (StandaloneProduct product : activeProducts) {
        if (ProductTraceSource.isProductMarked(traceSource, product.traceSourceBit)) {
          log.debug("Set SAMPLER_KEEP for {} span {}", product.name(), span.getSpanId());
          span.setSamplingPriority(SAMPLER_KEEP, product.samplingMechanism);
          return;
        }
      }
    }
    // APM-only trace: rate-limit for billing if required, otherwise drop.
    if (needsBillingTrace) {
      if (shouldSample()) {
        log.debug("Set SAMPLER_KEEP for billing APM span {}", span.getSpanId());
        span.setSamplingPriority(SAMPLER_KEEP, billingMechanism);
      } else {
        log.debug("Set SAMPLER_DROP for APM span {}", span.getSpanId());
        span.setSamplingPriority(SAMPLER_DROP, billingMechanism);
      }
    } else {
      log.debug("Set SAMPLER_DROP for APM-only span {}", span.getSpanId());
      span.setSamplingPriority(SAMPLER_DROP, SamplingMechanism.DEFAULT);
    }
  }

  private boolean shouldSample() {
    long now = clock.millis();
    return lastSampleTime.updateAndGet(
            lastTime -> now - lastTime >= RATE_IN_MILLISECONDS ? now : lastTime)
        == now;
  }
}
