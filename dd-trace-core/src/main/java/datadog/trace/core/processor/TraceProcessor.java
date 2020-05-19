package datadog.trace.core.processor;

import datadog.trace.api.Config;
import datadog.trace.core.DDSpan;
import datadog.trace.core.interceptor.TraceStatsCollector;
import datadog.trace.core.processor.rule.AnalyticsSampleRateRule;
import datadog.trace.core.processor.rule.DBStatementRule;
import datadog.trace.core.processor.rule.ErrorRule;
import datadog.trace.core.processor.rule.HttpStatusErrorRule;
import datadog.trace.core.processor.rule.ResourceNameRule;
import datadog.trace.core.processor.rule.SpanTypeRule;
import datadog.trace.core.processor.rule.Status404Rule;
import datadog.trace.core.processor.rule.URLAsResourceNameRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TraceProcessor {
  final Rule[] DEFAULT_RULES =
      new Rule[] {
        // Rules are applied in order.
        new DBStatementRule(),
        new ResourceNameRule(),
        new SpanTypeRule(),
        new ErrorRule(),
        new HttpStatusErrorRule(),
        new URLAsResourceNameRule(),
        new Status404Rule(),
        new AnalyticsSampleRateRule(),
      };

  private final List<Rule> rules;

  private final TraceStatsCollector statsCollector;

  public TraceProcessor(final TraceStatsCollector statsCollector) {
    this.statsCollector = statsCollector;

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

    void processSpan(DDSpan span, Map<String, Object> tags, Collection<DDSpan> trace);
  }

  public List<DDSpan> onTraceComplete(final List<DDSpan> trace) {
    /**
     * collect stats before applying rules because this is more realistic of what the trace will
     * look like when comparing data before completion.
     */
    statsCollector.onTraceComplete(trace);

    for (final DDSpan span : trace) {
      applyRules(trace, span);
    }

    // TODO: apply DDTracer's TraceInterceptors
    return trace;
  }

  private void applyRules(final Collection<DDSpan> trace, final DDSpan span) {
    final Map<String, Object> tags = span.getTags();
    for (final Rule rule : rules) {
      rule.processSpan(span, tags, trace);
    }
  }

  public TraceStatsCollector getTraceStatsCollector() {
    return statsCollector;
  }
}
