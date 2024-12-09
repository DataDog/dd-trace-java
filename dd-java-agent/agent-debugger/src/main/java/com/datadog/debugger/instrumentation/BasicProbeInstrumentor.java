package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TypeInsnNode;

public class BasicProbeInstrumentor extends Instrumentor {
  public BasicProbeInstrumentor(
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

    LabelNode endLabel = new LabelNode();
    methodNode.instructions.insert(methodNode.instructions.getFirst(), insnList);

    return InstrumentationResult.Status.INSTALLED;
  }

  private void pushProbesIds(InsnList insnList) {
    ldc(insnList, probeIds.size()); // array size
    // stack [int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, STRING_TYPE.getInternalName()));
    // stack [array]
    for (int i = 0; i < probeIds.size(); i++) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack [array, array]
      ldc(insnList, i); // index
      // stack [array, array, int]
      ldc(insnList, probeIds.get(i).getEncodedId());
      // stack [array, array, int, string]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack [array]
    }
  }
}
