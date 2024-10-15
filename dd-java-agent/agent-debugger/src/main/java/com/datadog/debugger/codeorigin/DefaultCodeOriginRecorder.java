package com.datadog.debugger.codeorigin;

import datadog.trace.bootstrap.debugger.DebuggerContext.CodeOriginRecorder;

public class DefaultCodeOriginRecorder implements CodeOriginRecorder {
  private final CodeOriginProbeManager probeManager;

  public DefaultCodeOriginRecorder(CodeOriginProbeManager probeManager) {
    this.probeManager = probeManager;
  }

  public CodeOriginProbeManager probeManager() {
    return probeManager;
  }

  @Override
  public String captureCodeOrigin(String identifier, boolean entry) {
    return probeManager.createProbe(identifier, entry);
  }
}
