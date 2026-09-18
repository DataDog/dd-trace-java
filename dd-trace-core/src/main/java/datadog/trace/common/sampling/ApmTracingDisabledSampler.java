package datadog.trace.common.sampling;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.core.CoreSpan;
import java.time.Clock;

/**
 * Drops APM traces for {@code dd.apm.tracing.enabled=false}, unless AppSec is activated while the
 * process is running, in which case it falls back to the standalone ASM trickle of 1 APM trace per
 * minute.
 *
 * <p>The sampler is picked once, when the tracer is built, but AppSec can be activated later by
 * Remote Configuration ({@code ASM_FEATURES}), which sets {@link ActiveSubsystems#APPSEC_ACTIVE}
 * rather than changing the immutable {@link datadog.trace.api.Config} the choice was made from. The
 * only place a sampler is rebuilt ({@code CoreTracer.ConfigSnapshot}) reacts to trace sampling rate
 * and rule changes and rebuilds from the initial config, so it would not notice either. Deciding
 * per trace instead keeps the service-catalog signal that standalone ASM needs once AppSec comes
 * up.
 *
 * <p>This only widens when the trickle is sent: the startup case is handled before this sampler is
 * ever built (see {@code Sampler.Builder.forConfig}), and AppSec is the only Application Security
 * product that can be activated at runtime — IAST instrumentation is installed in {@code premain}
 * and SCA is read once, so neither has a Remote Configuration product.
 */
public class ApmTracingDisabledSampler implements Sampler, PrioritySampler {

  private final AsmStandaloneSampler asmStandaloneSampler;
  private final ForcePrioritySampler dropSampler;

  public ApmTracingDisabledSampler(final Clock clock) {
    this.asmStandaloneSampler = new AsmStandaloneSampler(clock);
    this.dropSampler =
        new ForcePrioritySampler(PrioritySampling.SAMPLER_DROP, SamplingMechanism.DEFAULT);
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    // Both delegates keep sending the trace to the agent so it can collect stats on dropped traces.
    return isAsmActive() ? asmStandaloneSampler.sample(span) : dropSampler.sample(span);
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    if (isAsmActive()) {
      asmStandaloneSampler.setSamplingPriority(span);
    } else {
      dropSampler.setSamplingPriority(span);
    }
  }

  private static boolean isAsmActive() {
    return ActiveSubsystems.APPSEC_ACTIVE;
  }
}
