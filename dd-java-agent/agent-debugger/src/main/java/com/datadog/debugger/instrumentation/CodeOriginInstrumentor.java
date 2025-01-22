package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;

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

    methodNode.instructions.insert(methodEnterLabel, insnList);

    return InstrumentationResult.Status.INSTALLED;
  }
}
