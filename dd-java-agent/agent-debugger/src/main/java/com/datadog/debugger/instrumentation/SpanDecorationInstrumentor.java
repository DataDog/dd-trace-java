package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.SpanDecorationProbe;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class SpanDecorationInstrumentor extends CapturedContextInstrumentor {
  public SpanDecorationInstrumentor(
      SpanDecorationProbe probe,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {
    super(probe, classLoader, classNode, methodNode, diagnostics, probeIds, false, Limits.DEFAULT);
  }

  @Override
  public void instrument() {
    super.instrument();
  }
}
