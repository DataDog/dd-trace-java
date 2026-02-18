package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.List;

public class MultiCapturedContextInstrumenter extends CapturedContextInstrumenter {
  public MultiCapturedContextInstrumenter(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices,
      boolean captureSnapshot,
      boolean captureEntry,
      Limits limits) {
    super(definition, methodInfo, diagnostics, probeIndices, captureSnapshot, captureEntry, limits);
  }
}
