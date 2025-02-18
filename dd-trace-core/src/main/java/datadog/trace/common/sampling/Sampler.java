package datadog.trace.common.sampling;

import static datadog.trace.bootstrap.instrumentation.api.SamplerConstants.DROP;
import static datadog.trace.bootstrap.instrumentation.api.SamplerConstants.KEEP;

import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.api.sampling.SamplingRule;
import datadog.trace.core.CoreSpan;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main interface to sample a collection of traces. */
public interface Sampler {

  /**
   * Sample a collection of traces based on the parent span
   *
   * @param span the parent span with its context
   * @return true when the trace/spans has to be reported/written
   */
  <T extends CoreSpan<T>> boolean sample(T span);

  final class Builder {
    private static final Logger log = LoggerFactory.getLogger(Builder.class);

    public static Sampler forConfig(final Config config, final TraceConfig traceConfig) {
      Sampler sampler;
      if (config != null) {
        if (!config.isApmTracingEnabled() && isAsmEnabled(config)) {
          log.debug("APM is disabled. Only 1 trace per minute will be sent.");
          return new AsmStandaloneSampler(Clock.systemUTC());
        }
        final Map<String, String> serviceRules = config.getTraceSamplingServiceRules();
        final Map<String, String> operationRules = config.getTraceSamplingOperationRules();
        List<? extends SamplingRule.TraceSamplingRule> traceSamplingRules;
        if (null != traceConfig) {
          traceSamplingRules = traceConfig.getTraceSamplingRules();
        } else if (null != config.getTraceSamplingRules()) {
          traceSamplingRules =
              TraceSamplingRules.deserialize(config.getTraceSamplingRules()).getRules();
        } else {
          traceSamplingRules = Collections.emptyList();
        }
        boolean serviceRulesDefined = serviceRules != null && !serviceRules.isEmpty();
        boolean operationRulesDefined = operationRules != null && !operationRules.isEmpty();
        boolean traceSamplingRulesDefined = !traceSamplingRules.isEmpty();
        if ((serviceRulesDefined || operationRulesDefined) && traceSamplingRulesDefined) {
          log.warn(
              "Both {} and/or {} as well as {} are defined. Only {} will be used for rule-based sampling",
              TracerConfig.TRACE_SAMPLING_SERVICE_RULES,
              TracerConfig.TRACE_SAMPLING_OPERATION_RULES,
              TracerConfig.TRACE_SAMPLING_RULES,
              TracerConfig.TRACE_SAMPLING_RULES);
        }
        Double traceSampleRate =
            null != traceConfig ? traceConfig.getTraceSampleRate() : config.getTraceSampleRate();
        if (serviceRulesDefined
            || operationRulesDefined
            || traceSamplingRulesDefined
            || traceSampleRate != null) {
          try {
            sampler =
                RuleBasedTraceSampler.build(
                    serviceRules,
                    operationRules,
                    traceSamplingRules,
                    traceSampleRate,
                    config.getTraceRateLimit());
          } catch (final IllegalArgumentException e) {
            log.error("Invalid sampler configuration. Using AllSampler", e);
            sampler = new AllSampler();
          }
        } else if (config.isPrioritySamplingEnabled()) {
          if (KEEP.equalsIgnoreCase(config.getPrioritySamplingForce())) {
            log.debug("Force Sampling Priority to: SAMPLER_KEEP.");
            sampler =
                new ForcePrioritySampler(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
          } else if (DROP.equalsIgnoreCase(config.getPrioritySamplingForce())) {
            log.debug("Force Sampling Priority to: SAMPLER_DROP.");
            sampler =
                new ForcePrioritySampler(PrioritySampling.SAMPLER_DROP, SamplingMechanism.DEFAULT);
          } else {
            sampler = new RateByServiceTraceSampler();
          }
        } else {
          sampler = new AllSampler();
        }
      } else {
        sampler = new AllSampler();
      }
      return sampler;
    }

    private static boolean isAsmEnabled(Config config) {
      return config.getAppSecActivation() == ProductActivation.FULLY_ENABLED
          || config.getIastActivation() == ProductActivation.FULLY_ENABLED
          || config.isAppSecScaEnabled();
    }

    public static Sampler forConfig(final Properties config) {
      return forConfig(Config.get(config), null);
    }

    private Builder() {}
  }
}
