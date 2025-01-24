package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;

public class ExceptionInstrumentor extends CapturedContextInstrumentor {

  public ExceptionInstrumentor(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<ProbeId> probeIds) {
    super(definition, methodInfo, diagnostics, probeIds, true, false, Limits.DEFAULT);
  }

  @Override
  public InstrumentationResult.Status instrument() {
    processInstructions(); // fill returnHandlerLabel
    addFinallyHandler(methodEnterLabel, returnHandlerLabel);
    installFinallyBlocks();
    return InstrumentationResult.Status.INSTALLED;
  }

  @Override
  protected InsnList getBeforeReturnInsnList(AbstractInsnNode node) {
    return null;
  }

  @Override
  protected InsnList getReturnHandlerInsnList() {
    return new InsnList();
  }
}
