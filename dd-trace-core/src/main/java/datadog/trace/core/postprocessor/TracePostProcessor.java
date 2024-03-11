package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import java.util.List;

public interface TracePostProcessor {
  void process(List<DDSpan> trace, DDSpanContext context);
}
