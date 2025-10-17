package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;

public class ExceptionInstrumenter extends CapturedContextInstrumenter {

  public ExceptionInstrumenter(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices) {
    super(definition, methodInfo, diagnostics, probeIndices, true, false, Limits.DEFAULT);
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
