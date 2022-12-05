package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_SPAN_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.probe.SpanProbe;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class SpanInstrumentor extends Instrumentor {
  private String spanName;
  private int spanVar;

  public SpanInstrumentor(
      SpanProbe spanProbe,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    super(spanProbe, classLoader, classNode, methodNode, diagnostics);
    this.spanName = spanProbe.getName();
  }

  public void instrument() {
    if (isLineProbe) {
      fillLineMap();
    } else {
      spanVar = newVar(DEBUGGER_SPAN_TYPE);
      processInstructions();
      InsnList insnList = new InsnList();
      ldc(insnList, spanName); // stack: [string]
      pushTags(insnList, definition.getTags()); // stack: [string, tags]
      invokeStatic(
          insnList,
          DEBUGGER_CONTEXT_TYPE,
          "createSpan",
          DEBUGGER_SPAN_TYPE,
          STRING_TYPE,
          Types.asArray(STRING_TYPE, 1)); // tags
      // stack: [span]
      insnList.add(new VarInsnNode(Opcodes.ASTORE, spanVar)); // stack: []
      LabelNode initSpanLabel = new LabelNode();
      insnList.add(initSpanLabel);
      LabelNode endLabel = new LabelNode();
      methodNode.instructions.insert(methodNode.instructions.getLast(), endLabel);
      InsnList handler = new InsnList();
      LabelNode handlerLabel = new LabelNode();
      handler.add(handlerLabel);
      debuggerSpanFinish(handler);
      handler.add(new InsnNode(Opcodes.ATHROW));
      methodNode.instructions.add(handler);
      methodNode.tryCatchBlocks.add(
          new TryCatchBlockNode(initSpanLabel, endLabel, handlerLabel, null));
      methodNode.instructions.insert(methodEnterLabel, insnList);
    }
  }

  @Override
  protected InsnList getReturnHandlerInsnList() {
    InsnList insnList = new InsnList();
    debuggerSpanFinish(insnList);
    return insnList;
  }

  private void debuggerSpanFinish(InsnList insnList) {
    insnList.add(new VarInsnNode(Opcodes.ALOAD, spanVar));
    invokeInterface(insnList, DEBUGGER_SPAN_TYPE, "finish", Type.VOID_TYPE);
  }
}
