package datadog.trace.common.sampling;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.api.sampling.SamplingRule;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.SimpleRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RuleBasedTraceSampler<T extends CoreSpan<T>>
    implements Sampler, PrioritySampler, RemoteResponseListener {

  private static final Logger log = LoggerFactory.getLogger(RuleBasedTraceSampler.class);
  private final List<RateSamplingRule> samplingRules;
  private final PrioritySampler fallbackSampler;
  private final SimpleRateLimiter rateLimiter;
  private final long rateLimit;

  public static final String SAMPLING_RULE_RATE = "_dd.rule_psr";
  public static final String SAMPLING_LIMIT_RATE = "_dd.limit_psr";

  public RuleBasedTraceSampler(
      final List<RateSamplingRule> samplingRules,
      final int rateLimit,
      final PrioritySampler fallbackSampler) {
    this.samplingRules = samplingRules;
    this.fallbackSampler = fallbackSampler;
    rateLimiter = new SimpleRateLimiter(rateLimit);

    this.rateLimit = rateLimit;
  }

  public static RuleBasedTraceSampler build(
      final List<? extends SamplingRule.TraceSamplingRule> traceSamplingRules,
      final Double defaultRate,
      final int rateLimit) {
    return build(null, null, traceSamplingRules, defaultRate, rateLimit);
  }

  public static RuleBasedTraceSampler build(
      @Deprecated final Map<String, String> serviceRules,
      @Deprecated final Map<String, String> operationRules,
      final List<? extends SamplingRule.TraceSamplingRule> traceSamplingRules,
      final Double defaultRate,
      final int rateLimit) {
    return build(
        serviceRules,
        operationRules,
        traceSamplingRules,
        defaultRate,
        rateLimit,
        new RateByServiceTraceSampler());
  }

  /**
   * Builds a rule based sampler that delegates to {@code fallbackSampler} for spans that match no
   * rule.
   *
   * <p>The fallback is supplied by the caller so that a single agent rate sampler instance can be
   * shared across sampler rebuilds triggered by remote configuration. That instance is the one
   * registered to receive agent sampling rates, so reusing it keeps the rates that have already
   * been learned and keeps the rule miss path connected to the agent.
   */
  public static RuleBasedTraceSampler build(
      @Deprecated final Map<String, String> serviceRules,
      @Deprecated final Map<String, String> operationRules,
      final List<? extends SamplingRule.TraceSamplingRule> traceSamplingRules,
      final Double defaultRate,
      final int rateLimit,
      final PrioritySampler fallbackSampler) {

    final List<RateSamplingRule> samplingRules = new ArrayList<>();

    if (traceSamplingRules != null && !traceSamplingRules.isEmpty()) {
      if ((!serviceRules.isEmpty() || !operationRules.isEmpty())) {
        log.warn(
            "Both {} and/or {} as well as {} are defined. Only {} will be used for rule-based sampling",
            TracerConfig.TRACE_SAMPLING_SERVICE_RULES,
            TracerConfig.TRACE_SAMPLING_OPERATION_RULES,
            TracerConfig.TRACE_SAMPLING_RULES,
            TracerConfig.TRACE_SAMPLING_RULES);
      }
      // Ignore serviceRules & operationRules if traceSamplingRules are defined
      for (SamplingRule.TraceSamplingRule rule : traceSamplingRules) {
        RateSamplingRule.TraceSamplingRule samplingRule =
            new RateSamplingRule.TraceSamplingRule(
                rule.getService(),
                rule.getName(),
                rule.getResource(),
                rule.getTags(),
                new DeterministicSampler.TraceSampler(rule.getSampleRate()),
                samplingMechanism(rule.getProvenance()));
        samplingRules.add(samplingRule);
      }
    } else {
      // Take into account serviceRules & operationRules only if traceSamplingRules are NOT defined
      if (serviceRules != null) {
        for (final Entry<String, String> entry : serviceRules.entrySet()) {
          try {
            final double rateForEntry = Double.parseDouble(entry.getValue());
            final RateSamplingRule samplingRule =
                new RateSamplingRule.ServiceSamplingRule(
                    entry.getKey(), new DeterministicSampler.TraceSampler(rateForEntry));
            samplingRules.add(samplingRule);
          } catch (final NumberFormatException e) {
            log.error("Unable to parse rate for service: {}", entry, e);
          }
        }
      }

      if (operationRules != null) {
        for (final Entry<String, String> entry : operationRules.entrySet()) {
          try {
            final double rateForEntry = Double.parseDouble(entry.getValue());
            final RateSamplingRule samplingRule =
                new RateSamplingRule.OperationSamplingRule(
                    entry.getKey(), new DeterministicSampler.TraceSampler(rateForEntry));
            samplingRules.add(samplingRule);
          } catch (final NumberFormatException e) {
            log.error("Unable to parse rate for operation: {}", entry, e);
          }
        }
      }
    }

    // Per spec, defaultRate is treated as "rule".  Arguably a defaultRate set via RC should be
    // remote rule,
    // but that's not currenlty part of the spec.
    if (defaultRate != null) {
      final RateSamplingRule samplingRule =
          new RateSamplingRule.AlwaysMatchesSamplingRule(
              new DeterministicSampler.TraceSampler(defaultRate),
              SamplingMechanism.LOCAL_USER_RULE);
      samplingRules.add(samplingRule);
    }

    return new RuleBasedTraceSampler(samplingRules, rateLimit, fallbackSampler);
  }

  /**
   * Returns the sampler applying agent published rates to spans that match no rule, or {@code null}
   * when the fallback does not use agent rates.
   */
  public RateByServiceTraceSampler agentSampler() {
    return fallbackSampler instanceof RateByServiceTraceSampler
        ? (RateByServiceTraceSampler) fallbackSampler
        : null;
  }

  /**
   * Forwards agent sampling rates to the fallback sampler.
   *
   * <p>Without this the inner {@link RateByServiceTraceSampler} would never observe the rates
   * published by the agent, and every span matching no rule would be kept at a rate of 1.0.
   */
  @Override
  public void onResponse(
      final String endpoint, final Map<String, Map<String, Number>> responseJson) {
    if (fallbackSampler instanceof RemoteResponseListener) {
      ((RemoteResponseListener) fallbackSampler).onResponse(endpoint, responseJson);
    }
  }

  private static byte samplingMechanism(SamplingRule.Provenance provenance) {
    switch (provenance) {
      case DYNAMIC:
        return SamplingMechanism.REMOTE_ADAPTIVE_RULE;
      case CUSTOMER:
        return SamplingMechanism.REMOTE_USER_RULE;
      default:
        return SamplingMechanism.LOCAL_USER_RULE;
    }
  }

  @Override
  public <T extends CoreSpan<T>> boolean sample(final T span) {
    return true;
  }

  @Override
  public <T extends CoreSpan<T>> void setSamplingPriority(final T span) {
    RateSamplingRule matchedRule = null;

    for (final RateSamplingRule samplingRule : samplingRules) {
      if (samplingRule.matches(span)) {
        matchedRule = samplingRule;
        break;
      }
    }

    if (matchedRule == null) {
      fallbackSampler.setSamplingPriority(span);
    } else {
      if (matchedRule.sample(span)) {
        if (rateLimiter.tryAcquire()) {
          span.setSamplingPriority(
              PrioritySampling.USER_KEEP,
              SAMPLING_RULE_RATE,
              matchedRule.getSampler().getSampleRate(),
              matchedRule.getMechanism());
        } else {
          span.setSamplingPriority(
              PrioritySampling.USER_DROP,
              SAMPLING_RULE_RATE,
              matchedRule.getSampler().getSampleRate(),
              matchedRule.getMechanism());
        }
        span.setMetric(SAMPLING_LIMIT_RATE, rateLimit);
      } else {
        span.setSamplingPriority(
            PrioritySampling.USER_DROP,
            SAMPLING_RULE_RATE,
            matchedRule.getSampler().getSampleRate(),
            matchedRule.getMechanism());
      }
    }
  }
}
