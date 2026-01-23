package dd.test.trace.annotation;

import datadog.trace.api.DoNotTrace;
import datadog.trace.api.Trace;

public class DontTraceClass {
  @DoNotTrace
  public void muted() {
    normallyTraced();
  }

  @Trace
  public void normallyTraced() {}
}
