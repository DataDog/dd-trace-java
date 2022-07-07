package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.CAPTURED_VALUE;
import static com.datadog.debugger.instrumentation.Types.CAPTURE_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.CORRELATION_ACCESS_TYPE;
import static com.datadog.debugger.instrumentation.Types.OBJECT_TYPE;
import static com.datadog.debugger.instrumentation.Types.SNAPSHOTPROVIDER_TYPE;
import static com.datadog.debugger.instrumentation.Types.SNAPSHOT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;
import static com.datadog.debugger.instrumentation.Types.THROWABLE_TYPE;
import static org.objectweb.asm.Type.INT_TYPE;

import com.datadog.debugger.agent.SnapshotProbe;
import com.datadog.debugger.agent.Where;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CorrelationAccess;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.FieldExtractor;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.ValueConverter;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Handles generating instrumentation for snapshot method & line probes */
public final class MethodProbeInstrumentor extends Instrumentor {
  private final SnapshotProbe probe;
  private final LabelNode snapshotInitLabel = new LabelNode();
  private int snapshotVar = -1;
  private LabelNode returnHandlerLabel = null;

  public MethodProbeInstrumentor(
      SnapshotProbe probe,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    super(probe, classLoader, classNode, methodNode, diagnostics);
    this.probe = probe;
  }

  public void instrument() {
    if (isLineProbe) {
      fillLineMap();
      addLineCaptures(lineMap);
    } else {
      instrumentMethodEnter();
      instrumentTryCatchHandlers();
      processInstructions();
      addFinallyHandler(returnHandlerLabel);
    }
  }

  private void processInstructions() {
    AbstractInsnNode node = methodNode.instructions.getFirst();
    while (node != null && !node.equals(returnHandlerLabel)) {
      if (node.getType() == AbstractInsnNode.LINE) {
        lineMap.addLine((LineNumberNode) node);
      } else {
        node = processInstruction(node);
      }
      node = node.getNext();
    }
    if (returnHandlerLabel == null) {
      // if no return found, fallback to use the last instruction as last resort
      returnHandlerLabel = new LabelNode();
      methodNode.instructions.insert(methodNode.instructions.getLast(), returnHandlerLabel);
    }
  }

  private AbstractInsnNode processInstruction(AbstractInsnNode node) {
    switch (node.getOpcode()) {
      case Opcodes.RET:
      case Opcodes.RETURN:
      case Opcodes.IRETURN:
      case Opcodes.FRETURN:
      case Opcodes.LRETURN:
      case Opcodes.DRETURN:
      case Opcodes.ARETURN:
        {
          methodNode.instructions.insertBefore(
              node, collectSnapshotCapture(-1, Snapshot.Kind.RETURN, node));
          AbstractInsnNode prev = node.getPrevious();
          methodNode.instructions.remove(node);
          methodNode.instructions.insert(
              prev, new JumpInsnNode(Opcodes.GOTO, getReturnHandler(node)));
          return prev;
        }
    }
    return node;
  }

  private void addLineCaptures(LineMap lineMap) {
    Where.SourceLine[] targetLines = probe.getWhere().getSourceLines();
    if (targetLines == null) {
      // no line capture to perform
      return;
    }
    if (lineMap.isEmpty()) {
      reportError("Missing line debug information.");
      return;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();

      boolean isSingleLine = from == till;

      LabelNode beforeLabel = lineMap.getLineLabel(from);
      // single line N capture translates to line range (N, N+1)
      LabelNode afterLabel = lineMap.getLineLabel(till + (isSingleLine ? 1 : 0));
      if (beforeLabel == null && afterLabel == null) {
        reportError("No line info for " + (isSingleLine ? "line " : "range ") + sourceLine + ".");
      }
      if (beforeLabel != null) {
        InsnList insnList =
            collectSnapshotCapture(sourceLine.getFrom(), Snapshot.Kind.BEFORE, beforeLabel);
        insnList.add(commitSnapshot());
        methodNode.instructions.insertBefore(beforeLabel.getNext(), insnList);
      }
      if (afterLabel != null && !isSingleLine) {
        InsnList insnList =
            collectSnapshotCapture(sourceLine.getTill(), Snapshot.Kind.AFTER, afterLabel);
        methodNode.instructions.insert(afterLabel, insnList);
      }
    }
  }

  private LabelNode getReturnHandler(AbstractInsnNode exitNode) {
    // exit node must have been removed from the original instruction list
    assert exitNode.getNext() == null && exitNode.getPrevious() == null;
    if (returnHandlerLabel != null) {
      return returnHandlerLabel;
    }
    returnHandlerLabel = new LabelNode();
    methodNode.instructions.add(returnHandlerLabel);
    // stack top is return value (if any)
    InsnList handler = commitSnapshot();
    handler.add(exitNode); // stack: []
    methodNode.instructions.add(handler);
    return returnHandlerLabel;
  }

  private InsnList commitSnapshot() {
    InsnList handler = new InsnList();
    getSnapshot(handler); // stack: [snapshot]
    LabelNode targetNode = new LabelNode();
    handler.add(new InsnNode(Opcodes.DUP)); // stack: [snapshot, snapshot]
    handler.add(new JumpInsnNode(Opcodes.IFNULL, targetNode)); // stack: [snapshot]
    invokeVirtual(handler, SNAPSHOT_TYPE, "commit", Type.VOID_TYPE); // stack: []
    LabelNode gotoNode = new LabelNode();
    handler.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    handler.add(targetNode); // stack: [snapshot]
    handler.add(new InsnNode(Opcodes.POP)); // stack: []
    handler.add(gotoNode); // stack: []
    return handler;
  }

  private void addFinallyHandler(LabelNode endLabel) {
    // stack: [exception]
    if (methodNode.tryCatchBlocks == null) {
      methodNode.tryCatchBlocks = new ArrayList<>();
    }
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    handler.add(collectSnapshotCapture(-1, Snapshot.Kind.UNHANDLED_EXCEPTION, endLabel));
    handler.add(commitSnapshot()); // stack: [exception]
    handler.add(new InsnNode(Opcodes.ATHROW)); // stack: []
    methodNode.instructions.add(handler);
    methodNode.tryCatchBlocks.add(
        new TryCatchBlockNode(snapshotInitLabel, endLabel, handlerLabel, null));
  }

  private void instrumentMethodEnter() {
    methodNode.instructions.insert(
        snapshotInitLabel, collectSnapshotCapture(-1, Snapshot.Kind.ENTER, null));
  }

  private void instrumentTryCatchHandlers() {
    for (TryCatchBlockNode tryCatchBlockNode : methodNode.tryCatchBlocks) {
      methodNode.instructions.insert(
          tryCatchBlockNode.handler,
          collectSnapshotCapture(-1, Snapshot.Kind.HANDLED_EXCEPTION, tryCatchBlockNode.handler));
    }
  }

  private InsnList collectSnapshotCapture(int line, Snapshot.Kind kind, AbstractInsnNode location) {
    InsnList insnList = new InsnList();
    LabelNode targetLabel = new LabelNode();
    getSnapshot(insnList); // stack top: [snapshot]
    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [snapshot, snapshot]
    insnList.add(new JumpInsnNode(Opcodes.IFNULL, targetLabel)); // stack: [snapshot]
    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [snapshot, snapshot]
    invokeVirtual(
        insnList, SNAPSHOT_TYPE, "isCapturing", Type.BOOLEAN_TYPE); // stack: [snapshot, boolean]
    insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetLabel)); // skip if not capturing
    // stack: [snapshot]
    insnList.add(
        new TypeInsnNode(
            Opcodes.NEW,
            CAPTURE_CONTEXT_TYPE.getInternalName())); // stack: [snapshot, capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [snapshot, capturedcontext, capturedcontext]
    invokeConstructor(insnList, CAPTURE_CONTEXT_TYPE);
    collectArguments(insnList); // stack: [snapshot, capturedcontext]
    collectFields(insnList); // stack: [snapshot, capturecontetx]
    if (kind != Snapshot.Kind.UNHANDLED_EXCEPTION && kind != Snapshot.Kind.HANDLED_EXCEPTION) {
      /*
       * It makes no sense collecting local variables for exceptions - the ones contributing to the exception
       * are most likely to be outside of the scope in the exception handler block and there is no way to figure
       * out the originating location just from bytecode.
       */
      collectLocalVariables(location, insnList); // stack: [snapshot, capturedcontext]
    }
    if (kind == Snapshot.Kind.RETURN) {
      collectReturnValue(location, insnList); // stack: [snapshot, capturedcontext]
    } else if (kind == Snapshot.Kind.UNHANDLED_EXCEPTION
        || kind == Snapshot.Kind.HANDLED_EXCEPTION) {
      collectExceptionValue(location, insnList); // stack: [snapshot, capturedcontext]
    }
    addCapturedContext(insnList, kind, line);
    LabelNode skipTarget = new LabelNode();
    insnList.add(new JumpInsnNode(Opcodes.GOTO, skipTarget));
    insnList.add(targetLabel); // stack: [snapshot]
    insnList.add(new InsnNode(Opcodes.POP)); // stack: []
    insnList.add(skipTarget); // stack: []
    return insnList;
  }

  private void addCapturedContext(InsnList insnList, Snapshot.Kind kind, int line) {
    switch (kind) {
      case ENTER:
        invokeVirtual(
            insnList, SNAPSHOT_TYPE, "setEntry", Type.VOID_TYPE, CAPTURE_CONTEXT_TYPE); // stack: []
        break;
      case RETURN:
      case UNHANDLED_EXCEPTION:
        invokeVirtual(
            insnList, SNAPSHOT_TYPE, "setExit", Type.VOID_TYPE, CAPTURE_CONTEXT_TYPE); // stack: []
        break;
      case BEFORE:
      case AFTER:
        ldc(insnList, line);
        invokeVirtual(
            insnList,
            SNAPSHOT_TYPE,
            "addLine",
            Type.VOID_TYPE,
            CAPTURE_CONTEXT_TYPE,
            INT_TYPE); // stack: []
        break;
      case HANDLED_EXCEPTION:
        invokeVirtual(
            insnList,
            SNAPSHOT_TYPE,
            "addCaughtException",
            Type.VOID_TYPE,
            CAPTURE_CONTEXT_TYPE); // stack: []
        break;
    }
  }

  private void collectArguments(InsnList insnList) {
    // expected stack top: [capturedcontext]
    Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
    if (argTypes.length == 0) {
      // bail out if no args
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [capturedcontext, capturedcontext]
    ldc(insnList, argTypes.length); // stack: [capturedcontext, capturedcontext, int]
    insnList.add(
        new TypeInsnNode(
            Opcodes.ANEWARRAY,
            CAPTURED_VALUE.getInternalName())); // stack: [capturedcontext, capture, array]
    int counter = 0;
    int slot = isStatic ? 0 : 1;
    for (Type argType : argTypes) {
      String currentArgName = argumentNames[slot];
      if (currentArgName == null) {
        // if argument names are not resolved correctly let's assign p+arg_index
        currentArgName = "p" + counter;
      }
      insnList.add(
          new InsnNode(Opcodes.DUP)); // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++); // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(
          insnList,
          currentArgName); // stack: [capturedcontext, capturedcontext, array, array, int, string]
      ldc(
          insnList,
          argType.getClassName()); // stack: [capturedcontext, capturedcontext, array, array, int,
      // string, type_name]
      insnList.add(
          new VarInsnNode(
              argType.getOpcode(Opcodes.ILOAD),
              slot)); // stack: [capturedcontext, capturedcontext, array, array, int, string,
      // type_name, arg]
      tryBox(
          argType,
          insnList); // stack: [capturedcontext, capturedcontext, array, array, int, type_name,
      // object]
      addCapturedValueOf(
          insnList,
          probe.getCapture()); // stack: [capturedcontext, capturedcontext, array, array, int,
      // typed_value]
      insnList.add(
          new InsnNode(Opcodes.AASTORE)); // stack: [capturedcontext, capturedcontext, array]
      slot += argType.getSize();
    }
    invokeVirtual(
        insnList,
        CAPTURE_CONTEXT_TYPE,
        "addArguments",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1)); // // stack: [capturedcontext]
  }

  private void tryBox(Type type, InsnList insnList) {
    // expected stack top is the value to be boxed
    if (Types.isPrimitive(type)) {
      Type targetType = Types.getBoxingTargetType(type);
      invokeStatic(insnList, targetType, "valueOf", targetType, type);
    }
  }

  private void collectLocalVariables(AbstractInsnNode location, InsnList insnList) {
    // expected stack top: [capturedcontext]
    if (location == null) {
      // method capture, local variables are not initialized - bail out
      return;
    }

    if (methodNode.localVariables == null || methodNode.localVariables.isEmpty()) {
      if (!Config.get().isDebuggerInstrumentTheWorld()) {
        reportWarning("Missing local variable debug info");
      }
      // no local variables info - bail out
      return;
    }

    List<LocalVariableNode> applicableVars = new ArrayList<>();
    for (LocalVariableNode variableNode : methodNode.localVariables) {
      int idx = variableNode.index - localVarBaseOffset;
      if (idx >= argOffset && isInScope(variableNode, location)) {
        applicableVars.add(variableNode);
      }
    }

    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [capturedcontext, capturedcontext]
    ldc(insnList, applicableVars.size()); // stack: [capturedcontext, capturedcontext, int]
    insnList.add(
        new TypeInsnNode(
            Opcodes.ANEWARRAY,
            CAPTURED_VALUE.getInternalName())); // stack: [capturedcontext, capturedcontext, array]
    int idx = 0;
    for (LocalVariableNode variableNode : applicableVars) {
      insnList.add(
          new InsnNode(Opcodes.DUP)); // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, idx++); // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(
          insnList,
          variableNode.name); // stack: [capturedcontext, capturedcontext, array, array, int, name]
      Type varType = Type.getType(variableNode.desc);
      ldc(
          insnList,
          Type.getType(variableNode.desc)
              .getClassName()); // stack: [capturedcontext, capturedcontext, array, array, int,
      // name, type_name]
      insnList.add(
          new VarInsnNode(
              varType.getOpcode(Opcodes.ILOAD),
              variableNode
                  .index)); // stack: [capturedcontext, capturedcontext, array, array, int, name,
      // type_name, value]
      tryBox(
          varType, insnList); // stack: [capturedcontext, capturedcontext, array, array, int, name,
      // type_name, object]
      addCapturedValueOf(
          insnList,
          probe.getCapture()); // stack: [capturedcontext, capturedcontext, array, array, int,
      // typed_value]
      insnList.add(
          new InsnNode(Opcodes.AASTORE)); // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURE_CONTEXT_TYPE,
        "addLocals",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1)); // // stack: [capturedcontext]
  }

  private void collectReturnValue(AbstractInsnNode location, InsnList insnList) {
    if (location == null) {
      // no location provided - bail out
      return;
    }
    if (location.getOpcode() != Opcodes.IRETURN
        && location.getOpcode() != Opcodes.LRETURN
        && location.getOpcode() != Opcodes.FRETURN
        && location.getOpcode() != Opcodes.DRETURN
        && location.getOpcode() != Opcodes.ARETURN) {
      // no return value - bail out
      return;
    }
    // expected stack top is [ret_value, snapshot, capturedcontext]
    int snapshotVar = newVar(SNAPSHOT_TYPE);
    int captureVar = newVar(CAPTURE_CONTEXT_TYPE);
    insnList.add(new VarInsnNode(Opcodes.ASTORE, captureVar)); // stack: [ret_value, snapshot]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, snapshotVar)); // stack: [ret_value]
    Type returnType = Type.getReturnType(methodNode.desc);
    int retVar = newVar(returnType);
    if (returnType.getSize() == 2) {
      insnList.add(new InsnNode(Opcodes.DUP2));
    } else {
      insnList.add(new InsnNode(Opcodes.DUP));
    } // stack: [ret_value, ret_value]
    insnList.add(
        new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), retVar)); // stack: [ret_value]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, snapshotVar)); // stack: [ret_value, snapshot]
    insnList.add(
        new VarInsnNode(
            Opcodes.ALOAD, captureVar)); // stack: [ret_value, snapshot, capturedcontext]
    insnList.add(
        new InsnNode(
            Opcodes.DUP)); // stack: [ret_value, snapshot, capturedcontext, capturedcontext]
    ldc(insnList, null); // // stack: [ret_value, snapshot, capturedcontext, capturedcontext, null]
    ldc(
        insnList,
        returnType
            .getClassName()); // stack: [ret_value, snapshot, capturedcontext, capturedcontext,
    // null, type_name]
    insnList.add(
        new VarInsnNode(
            returnType.getOpcode(Opcodes.ILOAD),
            retVar)); // stack: [ret_value, snapshot, capturedcontext, capturedcontext, null,
    // type_name, ret_value]
    tryBox(
        returnType,
        insnList); // stack: [ret_value, snapshot, capturedcontext, capturedcontext, null,
    // type_name, ret_value]
    addCapturedValueOf(
        insnList,
        probe.getCapture()); // stack: [ret_value, snapshot, capturedcontext, capturedcontext,
    // typed_value]
    invokeVirtual(
        insnList,
        CAPTURE_CONTEXT_TYPE,
        "addReturn",
        Type.VOID_TYPE,
        CAPTURED_VALUE); // // stack: [ret_value, snapshot, capturedcontext]
  }

  private void collectExceptionValue(AbstractInsnNode location, InsnList insnList) {
    if (location == null) {
      // bail out
      return;
    }
    // expected stack: [throwable, snapshot, capturedcontext]
    int snapshotVar = newVar(SNAPSHOT_TYPE);
    int captureVar = newVar(CAPTURE_CONTEXT_TYPE);
    int throwableVar = newVar(THROWABLE_TYPE);
    insnList.add(new VarInsnNode(Opcodes.ASTORE, captureVar)); // stack: [throwable, snapshot]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, snapshotVar)); // stack: [throwable]
    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [throwable, throwable]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, throwableVar)); // stack: [throwable]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, snapshotVar)); // stack: [throwable, snapshot]
    insnList.add(
        new VarInsnNode(
            Opcodes.ALOAD, captureVar)); // stack: [throwable, snapshot, capturedcontext]
    insnList.add(
        new InsnNode(
            Opcodes.DUP)); // stack: [throwable, snapshot, capturedcontext, capturedcontext]
    insnList.add(
        new VarInsnNode(
            Opcodes.ALOAD,
            throwableVar)); // stack: [throwable, snapshot, capturedcontext, capturedcontext,
    // throwable]
    invokeVirtual(
        insnList,
        CAPTURE_CONTEXT_TYPE,
        "addThrowable",
        Type.VOID_TYPE,
        THROWABLE_TYPE); // stack: [throwable, snapshot, capturedcontext]
  }

  private void collectFields(InsnList insnList) {
    // expected stack top: [capturedcontext]

    /*
     * We are cheating a bit with CorrelationAccess - utilizing the knowledge that it is a singleton loaded by the
     * bootstrap class loader we can assume that the availability will not change during the app life time.
     * As a side effect, we happen to initialize the access here and not from the injected code.
     */
    boolean correlationAvailable = CorrelationAccess.instance().isAvailable();
    if (isStatic && !correlationAvailable) {
      // static method and no correlation info, no need to capture fields
      return;
    }
    // count instance fields
    int fieldCount = correlationAvailable ? 2 : 0; // correlation will add 2 synthetic fields
    if (!isStatic) {
      for (FieldNode fieldNode : classNode.fields) {
        if (isStaticField(fieldNode)) {
          continue;
        }
        fieldCount++;
      }
    }
    if (fieldCount == 0) {
      // bail out if no fields
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP)); // stack: [capturedcontext, capturedcontext]
    ldc(insnList, fieldCount); // stack: [capturedcontext, capturedcontext, int]
    insnList.add(
        new TypeInsnNode(
            Opcodes.ANEWARRAY,
            CAPTURED_VALUE.getInternalName())); // stack: [capturedcontext, capturedcontext, array]
    int counter = 0;
    List<FieldNode> fieldList = isStatic ? new ArrayList<>() : new ArrayList<>(classNode.fields);
    if (correlationAvailable) {
      fieldList.add(
          new FieldNode(
              Opcodes.ACC_PRIVATE, "dd.trace_id", STRING_TYPE.getDescriptor(), null, null));
      fieldList.add(
          new FieldNode(
              Opcodes.ACC_PRIVATE, "dd.span_id", STRING_TYPE.getDescriptor(), null, null));
    }
    for (FieldNode fieldNode : fieldList) {
      if (isStaticField(fieldNode)) {
        continue;
      }
      insnList.add(
          new InsnNode(Opcodes.DUP)); // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++); // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(
          insnList,
          fieldNode.name); // stack: [capturedcontext, capturedcontext, array, array, int, string]
      Type fieldType = Type.getType(fieldNode.desc);
      ldc(
          insnList,
          fieldType.getClassName()); // stack: [capturedcontext, capturedcontext, array, array, int,
      // string, type_name]
      switch (fieldNode.name) {
        case "dd.trace_id":
          {
            invokeStatic(
                insnList,
                CORRELATION_ACCESS_TYPE,
                "instance",
                Type.getType(
                    CorrelationAccess
                        .class)); // stack: [capturedcontext, capturedcontext, array, array, int,
            // string, type_name,
            // access]
            invokeVirtual(
                insnList,
                CORRELATION_ACCESS_TYPE,
                "getTraceId",
                Type.getType(
                    String.class)); // stack: [capturedcontext, capturedcontext, array, array, int,
            // string, type_name,
            // field_value]
            break;
          }
        case "dd.span_id":
          {
            invokeStatic(
                insnList,
                CORRELATION_ACCESS_TYPE,
                "instance",
                Type.getType(
                    CorrelationAccess
                        .class)); // stack: [capturedcontext, capturedcontext, array, array, int,
            // string, type_name,
            // access]
            invokeVirtual(
                insnList,
                CORRELATION_ACCESS_TYPE,
                "getSpanId",
                Type.getType(
                    String.class)); // stack: [capturedcontext, capturedcontext, array, array, int,
            // string, type_name,
            // field_value]
            break;
          }
        default:
          {
            insnList.add(
                new VarInsnNode(
                    Opcodes.ALOAD,
                    0)); // stack: [capturedcontext, capturedcontext, array, array, int, string,
            // type_name, this]
            insnList.add(
                new FieldInsnNode(
                    Opcodes.GETFIELD,
                    classNode.name,
                    fieldNode.name,
                    fieldNode
                        .desc)); // stack: [capturedcontext, capturedcontext, array, array, int,
            // string, type_name,
            // field_value]
          }
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
      // field_value]

      tryBox(
          fieldType,
          insnList); // stack: [capturedcontext, capturedcontext, array, array, int, type_name,
      // object]
      addCapturedValueOf(
          insnList,
          probe.getCapture()); // stack: [capturedcontext, capturedcontext, array, array, int,
      // typed_value]
      insnList.add(
          new InsnNode(Opcodes.AASTORE)); // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURE_CONTEXT_TYPE,
        "addFields",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1)); // stack: [capturedcontext]
  }

  private boolean isInScope(LocalVariableNode variableNode, AbstractInsnNode location) {
    AbstractInsnNode startScope =
        variableNode.start != null ? variableNode.start : methodNode.instructions.getFirst();
    AbstractInsnNode endScope =
        variableNode.end != null ? variableNode.end : methodNode.instructions.getLast();

    AbstractInsnNode insn = startScope;
    while (insn != null && insn != endScope) {
      if (insn == location) {
        return true;
      }
      insn = insn.getNext();
    }
    return false;
  }

  private void getSnapshot(InsnList insnList) {
    if (snapshotVar == -1) {
      snapshotVar = newVar(SNAPSHOT_TYPE);
      ldc(insnList, probe.getId());
      ldc(insnList, Type.getObjectType(classNode.name));
      invokeStatic(
          insnList, SNAPSHOTPROVIDER_TYPE, "newSnapshot", SNAPSHOT_TYPE, STRING_TYPE, CLASS_TYPE);
      insnList.add(new InsnNode(Opcodes.DUP));
      insnList.add(new VarInsnNode(Opcodes.ASTORE, snapshotVar));
      // init the snapshot holder with NULL and store the position where the variable is initialized
      // as a label
      InsnList initSnapshotVar = new InsnList();
      initSnapshotVar.add(new InsnNode(Opcodes.ACONST_NULL));
      initSnapshotVar.add(new VarInsnNode(Opcodes.ASTORE, snapshotVar));
      initSnapshotVar.add(snapshotInitLabel);
      methodNode.instructions.insert(methodEnterLabel, initSnapshotVar);
    } else {
      insnList.add(new VarInsnNode(Opcodes.ALOAD, snapshotVar));
    }
  }

  private void addCapturedValueOf(InsnList insnList, SnapshotProbe.Capture capture) {
    if (capture == null) {
      ldc(insnList, ValueConverter.DEFAULT_REFERENCE_DEPTH);
      ldc(insnList, ValueConverter.DEFAULT_COLLECTION_SIZE);
      ldc(insnList, ValueConverter.DEFAULT_LENGTH);
      ldc(insnList, FieldExtractor.DEFAULT_FIELD_DEPTH);
      ldc(insnList, FieldExtractor.DEFAULT_FIELD_COUNT);
    } else {
      ldc(insnList, capture.getMaxReferenceDepth());
      ldc(insnList, capture.getMaxCollectionSize());
      ldc(insnList, capture.getMaxLength());
      ldc(insnList, capture.getMaxFieldDepth());
      ldc(insnList, capture.getMaxFieldCount());
    }
    // expected stack: [type_name, value, int, int, int, int, int]
    invokeStatic(
        insnList,
        CAPTURED_VALUE,
        "of",
        CAPTURED_VALUE,
        STRING_TYPE,
        STRING_TYPE,
        OBJECT_TYPE,
        INT_TYPE,
        INT_TYPE,
        INT_TYPE,
        INT_TYPE,
        INT_TYPE); // stack: [captured_value]
  }

  private void invokeVirtual(
      InsnList insnList, Type owner, String name, Type returnType, Type... argTypes) {
    // expected stack: [this, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            owner.getInternalName(),
            name,
            Type.getMethodDescriptor(returnType, argTypes),
            false)); // stack: [ret_type]
  }

  private void invokeConstructor(InsnList insnList, Type owner, Type... argTypes) {
    // expected stack: [instance, arg_type_1 ... arg_type_N]
    insnList.add(
        new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            owner.getInternalName(),
            Types.CONSTRUCTOR,
            Type.getMethodDescriptor(Type.VOID_TYPE, argTypes),
            false)); // stack: []
  }

  private void newInstance(InsnList insnList, Type type) {
    insnList.add(new TypeInsnNode(Opcodes.NEW, type.getInternalName()));
  }

  private int newVar(Type type) {
    int varId = methodNode.maxLocals + (type.getSize());
    methodNode.maxLocals = varId;
    return varId;
  }
}
