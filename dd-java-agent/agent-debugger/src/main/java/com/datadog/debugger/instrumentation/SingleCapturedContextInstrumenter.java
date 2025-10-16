package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.getStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.CAPTURED_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.METHOD_LOCATION_TYPE;
import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getType;

import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.bootstrap.debugger.Limits;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Specialized version of {@link CapturedContextInstrumenter} for single probe */
public class SingleCapturedContextInstrumenter extends CapturedContextInstrumenter {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(SingleCapturedContextInstrumenter.class);
  private final boolean captureSnapshot;
  private final boolean captureEntry;
  private final Limits limits;

  public SingleCapturedContextInstrumenter(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices,
      boolean captureSnapshot,
      boolean captureEntry,
      Limits limits) {
    super(definition, methodInfo, diagnostics, probeIndices, captureSnapshot, captureEntry, limits);
    this.captureSnapshot = captureSnapshot;
    this.captureEntry = captureEntry;
    this.limits = limits;
  }

  @Override
  protected void addIsReadyToCaptureCall(InsnList insnList) {
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [class]
    ldc(insnList, probeIndices.get(0));
    // stack [class, int]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "isReadyToCapture",
        Type.BOOLEAN_TYPE,
        CLASS_TYPE,
        Type.INT_TYPE);
    // stack [boolean]
  }

  @Override
  protected void addEvalContextCall(
      InsnList insnList,
      Snapshot.Kind snapshotKind,
      AbstractInsnNode endLabel,
      int timestampVar,
      String methodLocation) {
    // stack []
    insnList.add(collectCapturedContext(snapshotKind, endLabel));
    // stack: [capturedcontext]
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [capturedcontext, class]
    if (timestampVar != -1) {
      insnList.add(new VarInsnNode(Opcodes.LLOAD, timestampVar));
    } else {
      ldc(insnList, -1L);
    }
    // stack [capturedcontext, class, long]
    getStatic(insnList, METHOD_LOCATION_TYPE, methodLocation);
    // stack [capturedcontext, class, long, methodlocation]
    ldc(insnList, probeIndices.get(0));
    // stack [capturedcontext, class, long, methodlocation, int]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "evalContext",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CLASS_TYPE,
        LONG_TYPE,
        METHOD_LOCATION_TYPE,
        Type.INT_TYPE);
    // stack []
  }

  @Override
  protected void addEvalContextAndCommitCall(
      Where.SourceLine sourceLine, InsnList insnList, LabelNode beforeLabel) {
    insnList.add(collectCapturedContext(Snapshot.Kind.BEFORE, beforeLabel));
    // stack [capturedcontext]
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [capturedcontext, class]
    ldc(insnList, sourceLine.getFrom());
    // stack [capturedcontext, class, int]
    ldc(insnList, probeIndices.get(0));
    // stack [capturedcontext, class, int, int]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "evalContextAndCommit",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CLASS_TYPE,
        INT_TYPE,
        INT_TYPE);
    // stack []
  }

  @Override
  protected void addCommitCall(InsnList insnList) {
    // stack [capturedcontext, capturedcontext, list]
    ldc(insnList, probeIndices.get(0));
    // stack [capturedcontext, capturedcontext, list, int]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "commit",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CAPTURED_CONTEXT_TYPE,
        getType(List.class),
        INT_TYPE);
    // stack []
  }
}
