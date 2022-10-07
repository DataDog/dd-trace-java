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

public interface SingleSpanSampler<T extends CoreSpan<T>> {

  boolean sample(T span);

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
            SPAN_SAMPLING_RULES);
      }

      if (spanSamplingRulesDefined) {
        SpanSamplingRules rules = SpanSamplingRules.deserialize(spanSamplingRules);
        if (rules != null) {
          return new RuleBasedSingleSpanSampler(rules);
        }
      } else if (spanSamplingRulesFileDefined) {
        // TODO read rules from the file
      }

      return null;
    }
  }

  final class RuleBasedSingleSpanSampler<T extends CoreSpan<T>> implements SingleSpanSampler<T> {
    private final List<SamplingRule.SpanSamplingRule<T>> spanSamplingRules;

    public RuleBasedSingleSpanSampler(SpanSamplingRules rules) {
      if (rules == null) {
        throw new NullPointerException("SpanSamplingRules can't be null.");
      }
      this.spanSamplingRules = new ArrayList<>();
      for (SpanSamplingRules.Rule rule : rules.getRules()) {
        RateSampler<T> sampler =
            new DeterministicSampler<>(
                rule.getSampleRate()); // TODO create a sampler. Maybe with a RateLimiter?
        SimpleRateLimiter simpleRateLimiter =
            rule.getMaxPerSecond() < Integer.MAX_VALUE
                ? new SimpleRateLimiter(rule.getMaxPerSecond())
                : null;
        SamplingRule.SpanSamplingRule<T> spanSamplingRule =
            new SamplingRule.SpanSamplingRule<>(
                rule.getService(), rule.getName(), sampler, simpleRateLimiter);
        spanSamplingRules.add(spanSamplingRule);
      }
    }

    @Override
    public boolean sample(T span) {
      for (SamplingRule.SpanSamplingRule<T> rule : spanSamplingRules) {
        if (rule.matches(span)) {
          if (rule.sample(span)) {
            rule.apply(span);
            // TODO apply the rule for the sampled span
            return true;
          }
          break;
        }
      }
      return false;
    }
  }
}
