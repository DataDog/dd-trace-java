package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

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
    // hoisting is required because exception instrumentation is wrapping the whole method body in
    // a try/catch that create a subscobe and even level method local vars are not accessible
    // in the catch clause for capture
    hoistedLocalVars = initAndHoistLocalVars(methodNode);
    Map<AbstractInsnNode, Frame<BasicValue>> frames =
        ASMHelper.computeFrames(classNode.name, methodNode);
    processInstructions(frames); // fill returnHandlerLabel
    addFinallyHandler(methodEnterLabel, returnHandlerLabel);
    installFinallyBlocks();
    return InstrumentationResult.Status.INSTALLED;
  }

  @Override
  protected InsnList getBeforeReturnInsnList(
      AbstractInsnNode node, Map<AbstractInsnNode, Frame<BasicValue>> frames) {
    return null;
  }

  @Override
  protected InsnList getReturnHandlerInsnList() {
    return new InsnList();
  }
}
