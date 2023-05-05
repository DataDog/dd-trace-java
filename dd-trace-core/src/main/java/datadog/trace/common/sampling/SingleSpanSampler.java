package datadog.trace.common.sampling;

import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES_FILE;

import datadog.trace.api.Config;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.util.SimpleRateLimiter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface SingleSpanSampler {

  <T extends CoreSpan<T>> boolean setSamplingPriority(T span);

  final class Builder {
    private static final Logger log = LoggerFactory.getLogger(Builder.class);

    public static SingleSpanSampler forConfig(Config config) {
      String spanSamplingRules = config.getSpanSamplingRules();
      String spanSamplingRulesFile = config.getSpanSamplingRulesFile();

      boolean spanSamplingRulesDefined = spanSamplingRules != null && !spanSamplingRules.isEmpty();
      boolean spanSamplingRulesFileDefined =
          spanSamplingRulesFile != null && !spanSamplingRulesFile.isEmpty();

      if (spanSamplingRulesDefined && spanSamplingRulesFileDefined) {
        log.warn(
            "Both {} and {} defined. {} will be ignored.",
            SPAN_SAMPLING_RULES,
            SPAN_SAMPLING_RULES_FILE,
            SPAN_SAMPLING_RULES_FILE);
      }

      if (spanSamplingRulesDefined) {
        SpanSamplingRules rules = SpanSamplingRules.deserialize(spanSamplingRules);
        if (!rules.isEmpty()) {
          return new RuleBasedSingleSpanSampler(rules);
        }
      } else if (spanSamplingRulesFileDefined) {
        SpanSamplingRules rules = SpanSamplingRules.deserializeFile(spanSamplingRulesFile);
        if (!rules.isEmpty()) {
          return new RuleBasedSingleSpanSampler(rules);
        }
      }

      return null;
    }

    private Builder() {}
  }

  final class RuleBasedSingleSpanSampler implements SingleSpanSampler {
    private final List<SamplingRule.SpanSamplingRule> spanSamplingRules;

    public RuleBasedSingleSpanSampler(SpanSamplingRules rules) {
      if (rules == null) {
        throw new NullPointerException("SpanSamplingRules can't be null.");
      }
      this.spanSamplingRules = new ArrayList<>();
      for (SpanSamplingRules.Rule rule : rules.getRules()) {
        RateSampler sampler = new DeterministicSampler.SpanSampler(rule.getSampleRate());
        SimpleRateLimiter simpleRateLimiter =
            rule.getMaxPerSecond() == Integer.MAX_VALUE
                ? null
                : new SimpleRateLimiter(rule.getMaxPerSecond());
        SamplingRule.SpanSamplingRule spanSamplingRule =
            new SamplingRule.SpanSamplingRule(
                rule.getService(), rule.getName(), sampler, simpleRateLimiter);
        spanSamplingRules.add(spanSamplingRule);
      }
    }

    @Override
    public <T extends CoreSpan<T>> boolean setSamplingPriority(T span) {
      for (SamplingRule.SpanSamplingRule rule : spanSamplingRules) {
        if (rule.matches(span)) {
          if (rule.sample(span)) {
            double rate = rule.getSampler().getSampleRate();
            SimpleRateLimiter rateLimiter = rule.getRateLimiter();
            int limit = rateLimiter == null ? Integer.MAX_VALUE : rateLimiter.getCapacity();
            span.setSpanSamplingPriority(rate, limit);
            return true;
          }
          break;
        }
      }
      return false;
    }
  }
}
