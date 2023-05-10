package com.datadog.debugger.sink;

import com.datadog.debugger.instrumentation.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.List;

public interface Sink {
  void addSnapshot(Snapshot snapshot);

  void addDiagnostics(ProbeId probeId, List<DiagnosticMessage> messages);

  void skipSnapshot(String probeId, DebuggerContext.SkipCause cause);
}
