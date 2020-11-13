package datadog.trace.common.sampling;

import static datadog.trace.bootstrap.instrumentation.api.SamplerConstants.DROP;
import static datadog.trace.bootstrap.instrumentation.api.SamplerConstants.KEEP;

import datadog.trace.api.Config;
import datadog.trace.core.CoreSpan;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/** Main interface to sample a collection of traces. */
public interface Sampler<T extends CoreSpan<T>> {

  /**
   * Sample a collection of traces based on the parent span
   *
   * @param span the parent span with its context
   * @return true when the trace/spans has to be reported/written
   */
  boolean sample(T span);

  @Slf4j
  final class Builder {
    public static <T extends CoreSpan<T>> Sampler<T> forConfig(final Config config) {
      Sampler<T> sampler;
      if (config != null) {
        final Map<String, String> serviceRules = config.getTraceSamplingServiceRules();
        final Map<String, String> operationRules = config.getTraceSamplingOperationRules();

        if ((serviceRules != null && !serviceRules.isEmpty())
            || (operationRules != null && !operationRules.isEmpty())
            || config.getTraceSampleRate() != null) {

          try {
            sampler =
                RuleBasedSampler.build(
                    serviceRules,
                    operationRules,
                    config.getTraceSampleRate(),
                    config.getTraceRateLimit());
          } catch (final IllegalArgumentException e) {
            log.error("Invalid sampler configuration. Using AllSampler", e);
            sampler = new AllSampler<>();
          }
        } else if (config.isPrioritySamplingEnabled()) {
          if (KEEP.equalsIgnoreCase(config.getPrioritySamplingForce())) {
            log.debug("Force Sampling Priority to: SAMPLER_KEEP.");
            sampler = new ForcePrioritySampler<>(PrioritySampling.SAMPLER_KEEP);
          } else if (DROP.equalsIgnoreCase(config.getPrioritySamplingForce())) {
            log.debug("Force Sampling Priority to: SAMPLER_DROP.");
            sampler = new ForcePrioritySampler<>(PrioritySampling.SAMPLER_DROP);
          } else {
            sampler = new RateByServiceSampler<>();
          }
        } else {
          sampler = new AllSampler<>();
        }
      } else {
        sampler = new AllSampler<>();
      }
      return sampler;
    }

    public static <T extends CoreSpan<T>> Sampler<T> forConfig(final Properties config) {
      return forConfig(Config.get(config));
    }

    private Builder() {}
  }
}
