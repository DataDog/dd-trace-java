package com.datadog.debugger.sink;

import datadog.trace.bootstrap.debugger.DebuggerContext;

public interface Sink {
  void addSnapshot(Snapshot snapshot);

  void skipSnapshot(String probeId, DebuggerContext.SkipCause cause);
}
