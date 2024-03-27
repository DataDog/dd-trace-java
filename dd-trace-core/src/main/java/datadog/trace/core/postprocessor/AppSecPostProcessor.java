package datadog.trace.core.postprocessor;

import datadog.trace.core.DDSpan;
import java.util.function.BooleanSupplier;

public class AppSecPostProcessor implements SpanPostProcessor {

  @Override
  public boolean process(DDSpan span, BooleanSupplier timeoutCheck) {
    // Do AppSec post-processing
    return true;
  }
}
