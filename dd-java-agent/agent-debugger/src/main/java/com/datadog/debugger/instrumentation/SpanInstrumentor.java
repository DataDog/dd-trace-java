package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeInterface;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_SPAN_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;
import static com.datadog.debugger.instrumentation.Types.THROWABLE_TYPE;
import static com.datadog.debugger.util.ClassFileHelper.stripPackagePath;

import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassFileLines;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

public class SpanInstrumentor extends Instrumentor {
  private int spanVar;

  public SpanInstrumentor(
      SpanProbe spanProbe,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices) {
    super(spanProbe, methodInfo, diagnostics, probeIndices);
  }

  @Override
  public InstrumentationResult.Status instrument() {
    if (definition.isLineProbe()) {
      return addRangeSpan(classFileLines);
    }
    spanVar = newVar(DEBUGGER_SPAN_TYPE);
    processInstructions();
    LabelNode initSpanLabel = new LabelNode();
    InsnList insnList = createSpan(initSpanLabel);
    LabelNode endLabel = new LabelNode();
    methodNode.instructions.insert(methodNode.instructions.getLast(), endLabel);
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = createCatchHandler(handlerLabel);
    methodNode.instructions.add(handler);
    methodNode.tryCatchBlocks.add(
        new TryCatchBlockNode(initSpanLabel, endLabel, handlerLabel, null));
    methodNode.instructions.insert(methodEnterLabel, insnList);
    return InstrumentationResult.Status.INSTALLED;
  }

  private InsnList createCatchHandler(LabelNode handlerLabel) {
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // stack [exception]
    handler.add(new InsnNode(Opcodes.DUP));
    // stack [exception, exception]
    handler.add(new VarInsnNode(Opcodes.ALOAD, spanVar));
    // stack [exception, exception, span]
    handler.add(new InsnNode(Opcodes.SWAP));
    // stack [exception, span, exception]
    invokeInterface(handler, DEBUGGER_SPAN_TYPE, "setError", Type.VOID_TYPE, THROWABLE_TYPE);
    // stack [exception]
    debuggerSpanFinish(handler);
    handler.add(new InsnNode(Opcodes.ATHROW));
    return handler;
  }

  private InsnList createSpan(LabelNode initSpanLabel) {
    InsnList insnList = new InsnList();
    ldc(insnList, definition.getProbeId().getEncodedId());
    // stack: [string]
    ldc(insnList, buildResourceName());
    // stack: [string, string]
    pushTags(insnList, addProbeIdWithTags(definition.getId(), definition.getTags()));
    // stack: [string, string, tags]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "createSpan",
        DEBUGGER_SPAN_TYPE,
        STRING_TYPE,
        STRING_TYPE,
        Types.asArray(STRING_TYPE, 1)); // tags
    // stack: [span]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, spanVar));
    // stack: []
    insnList.add(initSpanLabel);
    return insnList;
  }

  private InstrumentationResult.Status addRangeSpan(ClassFileLines classFileLines) {
    Where.SourceLine[] targetLines = definition.getWhere().getSourceLines();
    if (targetLines == null || targetLines.length == 0) {
      reportError("Missing line(s) in probe definition.");
      return InstrumentationResult.Status.ERROR;
    }
    if (classFileLines.isEmpty()) {
      reportError("Missing line debug information.");
      return InstrumentationResult.Status.ERROR;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();
      if (from == till) {
        reportError("Single line span is not supported, you need to provide a range.");
        return InstrumentationResult.Status.ERROR;
      }
      LabelNode beforeLabel = classFileLines.getLineLabel(from);
      LabelNode afterLabel = classFileLines.getLineLabel(till);
      if (beforeLabel == null || afterLabel == null) {
        reportError(
            "No line info for " + (sourceLine.isSingleLine() ? "line " : "range ") + sourceLine);
        return InstrumentationResult.Status.ERROR;
      }
      spanVar = newVar(DEBUGGER_SPAN_TYPE);
      LabelNode initSpanLabel = new LabelNode();
      InsnList createSpaninsnList = createSpan(initSpanLabel);
      methodNode.instructions.insertBefore(beforeLabel.getNext(), createSpaninsnList);
      LabelNode handlerLabel = new LabelNode();
      InsnList handler = createCatchHandler(handlerLabel);
      methodNode.instructions.add(handler);
      methodNode.tryCatchBlocks.add(
          new TryCatchBlockNode(initSpanLabel, afterLabel, handlerLabel, null));
      InsnList finishSpanInsnList = new InsnList();
      debuggerSpanFinish(finishSpanInsnList);
      methodNode.instructions.insert(afterLabel, finishSpanInsnList);
    }
    return InstrumentationResult.Status.INSTALLED;
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

  private String buildResourceName() {
    String resourceName = stripPackagePath(classNode.name) + "." + methodNode.name;
    if (definition.isLineProbe()) {
      Where.SourceLine[] targetLines = definition.getWhere().getSourceLines();
      if (targetLines == null || targetLines.length == 0) {
        return resourceName;
      }
      if (classFileLines.isEmpty()) {
        return resourceName;
      }
      return resourceName + ":L" + targetLines[0];
    }
    return resourceName;
  }
}
