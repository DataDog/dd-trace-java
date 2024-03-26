package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import java.util.function.BooleanSupplier;

public class AppSecPostProcessor implements SpanPostProcessor {

  @Override
  public boolean process(DDSpan span, DDSpanContext context, BooleanSupplier timeoutCheck) {
    // Do AppSec post-processing
    return true;
  }
}
