package datadog.trace.core.processor;

import datadog.trace.api.Config;
import datadog.trace.core.DDSpan;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.rule.AnalyticsSampleRateRule;
import datadog.trace.core.processor.rule.URLAsResourceNameRule;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TraceProcessor {
  final Rule[] DEFAULT_RULES =
      new Rule[] {
        // Rules are applied in order.
        new URLAsResourceNameRule(), new AnalyticsSampleRateRule(),
      };

  private final List<Rule> rules;

  public TraceProcessor() {

    rules = new ArrayList<>(DEFAULT_RULES.length);
    for (final Rule rule : DEFAULT_RULES) {
      if (isEnabled(rule)) {
        rules.add(rule);
      }
    }
  }

  private static boolean isEnabled(final Rule rule) {
    boolean enabled = Config.get().isRuleEnabled(rule.getClass().getSimpleName());
    for (final String alias : rule.aliases()) {
      enabled &= Config.get().isRuleEnabled(alias);
    }
    if (!enabled) {
      log.debug("{} disabled", rule.getClass().getSimpleName());
    }
    return enabled;
  }

  public interface Rule {
    String[] aliases();

    void processSpan(ExclusiveSpan span);
  }

  public List<DDSpan> onTraceComplete(final List<DDSpan> trace) {
    for (final DDSpan span : trace) {
      applyRules(span);
    }

    // TODO: apply DDTracer's TraceInterceptors
    return trace;
  }

  private void applyRules(final DDSpan span) {
    if (rules.size() > 0) {
      span.context()
          .processExclusiveSpan(
              new ExclusiveSpan.Consumer() {
                @Override
                public void accept(ExclusiveSpan span) {
                  for (final Rule rule : rules) {
                    rule.processSpan(span);
                  }
                }
              });
    }
  }
}
