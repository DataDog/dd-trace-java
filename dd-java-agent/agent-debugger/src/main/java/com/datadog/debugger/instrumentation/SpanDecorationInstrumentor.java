package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.SpanDecorationProbe;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.List;

public class SpanDecorationInstrumentor extends CapturedContextInstrumentor {
  public SpanDecorationInstrumentor(
      SpanDecorationProbe probe,
      InstrumentationContext instrumentationContext,
      List<String> probeIds) {
    super(probe, instrumentationContext, probeIds, false, Limits.DEFAULT);
  }

  @Override
  public void instrument() {
    super.instrument();
  }
}
