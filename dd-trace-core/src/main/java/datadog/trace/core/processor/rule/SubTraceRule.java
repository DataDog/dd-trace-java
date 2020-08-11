package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.SubTrace;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class SubTraceRule implements TraceProcessor.Rule {
  private static final int LIMIT = 10;

  @Override
  public String[] aliases() {
    return new String[] {};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    final Collection<SubTrace> subTraces = span.getSubTraces().values();
    final List<SubTrace> sorted = new ArrayList<>(subTraces.size());
    sorted.addAll(subTraces);
    Collections.sort(sorted);
    for (int i = 0; i < Math.min(LIMIT, sorted.size()); i++) {
      final SubTrace subTrace = sorted.get(i);
      span.setTag("subtrace." + i + "." + subTrace.tagKey(), subTrace.tagValue());
    }
  }
}
