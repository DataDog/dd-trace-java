package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import java.util.function.BooleanSupplier;

public interface SpanPostProcessor {
  boolean process(DDSpan trace, DDSpanContext context, BooleanSupplier timeoutCheck);
}
