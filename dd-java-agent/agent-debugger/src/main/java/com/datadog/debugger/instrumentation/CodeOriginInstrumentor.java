package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LineNumberNode;

public class CodeOriginInstrumentor extends Instrumentor {
  public CodeOriginInstrumentor(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<ProbeId> probeIds) {
    super(definition, methodInfo, diagnostics, probeIds);
  }

  @Override
  public Status instrument() {
    InsnList insnList = new InsnList();

    ldc(insnList, probeIds.get(0).getEncodedId());
    invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "codeOrigin", Type.VOID_TYPE, STRING_TYPE);
    methodNode.instructions.insert(findInsertionPoint(), insnList);

    return InstrumentationResult.Status.INSTALLED;
  }

  private AbstractInsnNode findInsertionPoint() {
    CodeOriginProbe probe = (CodeOriginProbe) definition;
    List<String> lines = probe.getLocation().getLines();
    if (!probe.entrySpanProbe() && lines != null && !lines.isEmpty()) {
      int line = Integer.parseInt(lines.get(0));
      for (AbstractInsnNode node : methodNode.instructions) {
        if (node instanceof LineNumberNode && ((LineNumberNode) node).line == line) {
          return node;
        }
      }
    }
    return methodEnterLabel;
  }
}
