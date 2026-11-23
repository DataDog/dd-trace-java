package datadog.trace.common.sampling;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.core.CoreSpan;
import java.time.Clock;

/**
 * Drops APM traces for {@code dd.apm.tracing.enabled=false}. If Remote Configuration activates
 * AppSec at runtime, falls back to the standalone ASM trickle of 1 APM trace per minute, so it
 * checks {@link ActiveSubsystems#APPSEC_ACTIVE} per trace.
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
