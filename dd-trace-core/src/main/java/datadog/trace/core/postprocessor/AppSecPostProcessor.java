package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import java.util.List;

public class AppSecPostProcessor implements TracePostProcessor {
  @Override
  public void process(List<DDSpan> trace, DDSpanContext context) {
    // Do AppSec post-processing
  }
}
