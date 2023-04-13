package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.probe.LogProbe.Capture.toLimits;

import com.datadog.debugger.probe.LogProbe;
import java.util.List;

/** Handles generating instrumentation for snapshot/log method & line probes */
public final class LogInstrumentor extends CapturedContextInstrumentor {
  private final LogProbe.Capture capture;

  public LogInstrumentor(
      LogProbe logProbe, InstrumentationContext instrumentationContext, List<String> probeIds) {
    super(
        logProbe,
        instrumentationContext,
        probeIds,
        logProbe.isCaptureSnapshot(),
        toLimits(logProbe.getCapture()));
    this.capture = logProbe.getCapture();
  }

  @Override
  public void instrument() {
    super.instrument();
  }
}
