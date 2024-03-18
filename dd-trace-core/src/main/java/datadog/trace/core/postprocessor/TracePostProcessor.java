package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface TracePostProcessor {
  boolean process(List<DDSpan> trace, DDSpanContext context, BooleanSupplier timeoutCheck);
}
