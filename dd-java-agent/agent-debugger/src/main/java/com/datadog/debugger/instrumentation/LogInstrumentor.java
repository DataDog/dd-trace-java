package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.probe.LogProbe.Capture.toLimits;

import com.datadog.debugger.probe.LogProbe;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Handles generating instrumentation for snapshot/log method & line probes */
public final class LogInstrumentor extends CapturedContextInstrumentor {
  private final LogProbe.Capture capture;

  public LogInstrumentor(
      LogProbe logProbe,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {
    super(
        logProbe,
        classLoader,
        classNode,
        methodNode,
        diagnostics,
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
