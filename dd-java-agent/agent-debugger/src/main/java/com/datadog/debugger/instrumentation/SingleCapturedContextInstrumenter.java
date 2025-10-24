package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.getStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.hasReturnValue;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.isStaticMethod;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.CAPTURED_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.METHOD_LOCATION_TYPE;
import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getType;

import com.datadog.debugger.el.expressions.ValueRefExpression;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Specialized version of {@link CapturedContextInstrumenter} for single probe */
public class SingleCapturedContextInstrumenter extends CapturedContextInstrumenter {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(SingleCapturedContextInstrumenter.class);

  public SingleCapturedContextInstrumenter(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices,
      boolean captureSnapshot,
      boolean captureEntry,
      Limits limits) {
    super(definition, methodInfo, diagnostics, probeIndices, captureSnapshot, captureEntry, limits);
  }

  @Override
  public InstrumentationResult.Status instrument() {
    if (hasCondition) {
      // run analysis to determine special variable usage (@exception, @duration)
      // for @duration only atExit and catch uncaught
      // for @exception, we assume the following:
      //   if you use @exception, you are not using @return on the same exception
      //   technically you can do not(isDefined(@exception)) and @return == 'foo', but this is
      //   useless. So we are considering @exception and @return exclusive
      // therefore the moment you are referencing @exception you will only capture in the catch for
      // uncaught exception for method probe
      // arguments are passed to the generated method
      // for local vars TODO: probably passed them as args to the generated method once hoisted
      // There is still one case for a void method (no @return) and we want to capture only
      // if no exception: we need to pattern match on not(isDefined(@exception)) alone and push the
      // generation of the method at exit instead of catch uncaught
      ConditionAnalysisVisitor visitor = new ConditionAnalysisVisitor();
      ((LogProbe) definition).getProbeCondition().accept(visitor);
      if (visitor.useException) {
        // only generate condition for exception
        conditionExceptionMethod =
            ConditionInstrumenter.generateConditionExceptionMethod(
                definition.getId(),
                probeIndices.get(0),
                ((LogProbe) definition).getProbeCondition(),
                classLoader,
                classNode,
                methodNode);
        classNode.methods.add(conditionExceptionMethod);
      } else {
        conditionMethod =
            ConditionInstrumenter.generateConditionMethod(
                definition.getId(),
                probeIndices.get(0),
                ((LogProbe) definition).getProbeCondition(),
                classLoader,
                classNode,
                methodNode,
                definition.getEvaluateAt() == MethodLocation.EXIT);
        classNode.methods.add(conditionMethod);
      }
    }
    return super.instrument();
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
        METHOD_LOCATION_TYPE,
        INT_TYPE);
    // stack []
  }

  @Override
  protected void addBeforeReturnCondition(InsnList insnList, LabelNode targetNode) {
    if (hasCondition
        && definition.getEvaluateAt() == MethodLocation.EXIT
        && conditionMethod != null) {
      addConditionCall(insnList, conditionMethod, targetNode, false);
    }
    // if no condition or not exit, do nothing
    // TODO check this is correct
  }

  @Override
  protected LabelNode addFinallyHandlerCondition(InsnList handler) {
    LabelNode targetNode = null;
    if (hasCondition && conditionExceptionMethod != null) {
      targetNode = new LabelNode();
      addConditionCall(handler, conditionExceptionMethod, targetNode, true);
    }
    return targetNode;
  }

  private void addConditionCall(
      InsnList insnList, MethodNode conditionMethod, LabelNode targetNode, boolean useException) {
    boolean isStatic = isStaticMethod(conditionMethod);
    boolean hasReturnValueOrException = hasReturnValue(methodNode) || useException;
    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    int argOffset = isStatic ? 0 : 1;
    if (hasReturnValueOrException) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack [ret_value, ret_value]
    }
    if (!isStatic) {
      // push this
      insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
      // stack [ret_value, ret_value, this]
      if (hasReturnValueOrException) {
        insnList.add(new InsnNode(Opcodes.SWAP));
        // stack [ret_value, this, ret_value]
      }
    }
    for (Type argType : argumentTypes) {
      insnList.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), argOffset));
      argOffset += argType.getSize();
    }
    // push timestamp start
    insnList.add(new VarInsnNode(Opcodes.LLOAD, timestampStartVar));
    // stack [ret_Value, (this), (ret_value), args..., timestamp]
    insnList.add(
        new MethodInsnNode(
            isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
            classNode.name,
            conditionMethod.name,
            conditionMethod.desc,
            false));
    // stack [ret_value, boolean]
    insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
  }

  private static class ConditionAnalysisVisitor extends RefAnalysisVisitor {
    private boolean useException;
    private boolean useDuration;

    @Override
    public Void visit(ValueRefExpression valueRefExpression) {
      switch (valueRefExpression.getSymbolName()) {
        case "@exception":
          useException = true;
          break;
        case "@duration":
          useDuration = true;
          break;
        default:
          break;
      }
      return null;
    }
  }
}
