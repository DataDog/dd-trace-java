package datadog.trace.common.sampling;

import static datadog.trace.bootstrap.instrumentation.api.SamplerConstants.DROP;
import static datadog.trace.bootstrap.instrumentation.api.SamplerConstants.KEEP;

import datadog.trace.api.Config;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.CoreSpan;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main interface to sample a collection of traces. */
public interface Sampler<T extends CoreSpan<T>> {

  /**
   * Sample a collection of traces based on the parent span
   *
   * @param span the parent span with its context
   * @return true when the trace/spans has to be reported/written
   */
  boolean sample(T span);

  final class Builder {
    private static final Logger log = LoggerFactory.getLogger(Builder.class);

    public static <T extends CoreSpan<T>> Sampler<T> forConfig(final Config config) {
      Sampler<T> sampler;
      if (config != null) {
        final Map<String, String> serviceRules = config.getTraceSamplingServiceRules();
        final Map<String, String> operationRules = config.getTraceSamplingOperationRules();
        String traceSamplingRulesJson = config.getTraceSamplingRules();
        TraceSamplingRules traceSamplingRules = null;
        if (traceSamplingRulesJson != null) {
          try {
            traceSamplingRules = TraceSamplingRules.deserialize(traceSamplingRulesJson);
          } catch (IOException e) {
            log.error("Couldn't parse trace sampling rules from JSON", e);
          }
        }
        boolean serviceRulesDefined = serviceRules != null && !serviceRules.isEmpty();
        boolean operationRulesDefined = operationRules != null && !operationRules.isEmpty();
        boolean jsonTraceSamplingRulesDefined =
            traceSamplingRules != null && !traceSamplingRules.isEmpty();
        if ((serviceRulesDefined || operationRulesDefined) && jsonTraceSamplingRulesDefined) {
          log.warn(
              "Both {} and/or {} as well as {} are defined. Only {} will be used for rule-based sampling",
              TracerConfig.TRACE_SAMPLING_SERVICE_RULES,
              TracerConfig.TRACE_SAMPLING_OPERATION_RULES,
              TracerConfig.TRACE_SAMPLING_RULES,
              TracerConfig.TRACE_SAMPLING_RULES);
        }
        if (serviceRulesDefined
            || operationRulesDefined
            || jsonTraceSamplingRulesDefined
            || config.getTraceSampleRate() != null) {
          try {
            sampler =
                RuleBasedSampler.build(
                    serviceRules,
                    operationRules,
                    traceSamplingRules,
                    config.getTraceSampleRate(),
                    config.getTraceRateLimit());
          } catch (final IllegalArgumentException e) {
            log.error("Invalid sampler configuration. Using AllSampler", e);
            sampler = new AllSampler<>();
          }
        } else if (config.isPrioritySamplingEnabled()) {
          if (KEEP.equalsIgnoreCase(config.getPrioritySamplingForce())) {
            log.debug("Force Sampling Priority to: SAMPLER_KEEP.");
            sampler =
                new ForcePrioritySampler<>(
                    PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
          } else if (DROP.equalsIgnoreCase(config.getPrioritySamplingForce())) {
            log.debug("Force Sampling Priority to: SAMPLER_DROP.");
            sampler =
                new ForcePrioritySampler<>(
                    PrioritySampling.SAMPLER_DROP, SamplingMechanism.DEFAULT);
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
