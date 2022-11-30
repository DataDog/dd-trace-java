package datadog.trace.common.sampling;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.sampling.SamplingRule.AlwaysMatchesSamplingRule;
import datadog.trace.common.sampling.SamplingRule.OperationSamplingRule;
import datadog.trace.common.sampling.SamplingRule.ServiceSamplingRule;
import datadog.trace.common.sampling.SamplingRule.TraceSamplingRule;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.SimpleRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RuleBasedSampler<T extends CoreSpan<T>> implements Sampler<T>, PrioritySampler<T> {

  private static final Logger log = LoggerFactory.getLogger(RuleBasedSampler.class);
  private final List<SamplingRule<T>> samplingRules;
  private final PrioritySampler<T> fallbackSampler;
  private final SimpleRateLimiter rateLimiter;
  private final long rateLimit;

  public static final String SAMPLING_RULE_RATE = "_dd.rule_psr";
  public static final String SAMPLING_LIMIT_RATE = "_dd.limit_psr";

  public RuleBasedSampler(
      final List<SamplingRule<T>> samplingRules,
      final int rateLimit,
      final PrioritySampler<T> fallbackSampler) {
    this.samplingRules = samplingRules;
    this.fallbackSampler = fallbackSampler;
    rateLimiter = new SimpleRateLimiter(rateLimit);

    this.rateLimit = rateLimit;
  }

  public static <T extends CoreSpan<T>> RuleBasedSampler<T> build(
      final TraceSamplingRules traceSamplingRules, final Double defaultRate, final int rateLimit) {
    return build(null, null, traceSamplingRules, defaultRate, rateLimit);
  }

  public static <T extends CoreSpan<T>> RuleBasedSampler<T> build(
      @Deprecated final Map<String, String> serviceRules,
      @Deprecated final Map<String, String> operationRules,
      final TraceSamplingRules traceSamplingRules,
      final Double defaultRate,
      final int rateLimit) {

    final List<SamplingRule<T>> samplingRules = new ArrayList<>();

    if (traceSamplingRules != null) {
      if ((!serviceRules.isEmpty() || !operationRules.isEmpty()) && !traceSamplingRules.isEmpty()) {
        log.warn(
            "Both {} and/or {} as well as {} are defined. Only {} will be used for rule-based sampling",
            TracerConfig.TRACE_SAMPLING_SERVICE_RULES,
            TracerConfig.TRACE_SAMPLING_OPERATION_RULES,
            TracerConfig.TRACE_SAMPLING_RULES,
            TracerConfig.TRACE_SAMPLING_RULES);
      }
      // Ignore serviceRules & operationRules if traceSamplingRules are defined
      for (TraceSamplingRules.Rule rule : traceSamplingRules.getRules()) {
        TraceSamplingRule<T> samplingRule =
            new TraceSamplingRule<>(
                rule.getService(),
                rule.getName(),
                new DeterministicSampler<T>(rule.getSampleRate()));
        samplingRules.add(samplingRule);
      }
    } else {
      // Take into account serviceRules & operationRules only if traceSamplingRules are NOT defined
      if (serviceRules != null) {
        for (final Entry<String, String> entry : serviceRules.entrySet()) {
          try {
            final double rateForEntry = Double.parseDouble(entry.getValue());
            final SamplingRule<T> samplingRule =
                new ServiceSamplingRule<>(
                    entry.getKey(), new DeterministicSampler<T>(rateForEntry));
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
            final SamplingRule<T> samplingRule =
                new OperationSamplingRule<>(
                    entry.getKey(), new DeterministicSampler<T>(rateForEntry));
            samplingRules.add(samplingRule);
          } catch (final NumberFormatException e) {
            log.error("Unable to parse rate for operation: {}", entry, e);
          }
        }
      }
    }

    if (defaultRate != null) {
      final SamplingRule<T> samplingRule =
          new AlwaysMatchesSamplingRule<>(new DeterministicSampler<T>(defaultRate));
      samplingRules.add(samplingRule);
    }

    return new RuleBasedSampler<>(samplingRules, rateLimit, new RateByServiceSampler<T>());
  }

  @Override
  public boolean sample(final T span) {
    return true;
  }

  @Override
  public void setSamplingPriority(final T span) {
    SamplingRule<T> matchedRule = null;

    for (final SamplingRule<T> samplingRule : samplingRules) {
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
              SamplingMechanism.RULE);
        } else {
          span.setSamplingPriority(
              PrioritySampling.USER_DROP,
              SAMPLING_RULE_RATE,
              matchedRule.getSampler().getSampleRate(),
              SamplingMechanism.RULE);
        }
        span.setMetric(SAMPLING_LIMIT_RATE, rateLimit);
      } else {
        span.setSamplingPriority(
            PrioritySampling.USER_DROP,
            SAMPLING_RULE_RATE,
            matchedRule.getSampler().getSampleRate(),
            SamplingMechanism.RULE);
      }
    }
  }
}
