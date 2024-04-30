package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.getStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeConstructor;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeVirtual;
import static com.datadog.debugger.instrumentation.ASMHelper.isFinalField;
import static com.datadog.debugger.instrumentation.ASMHelper.isStaticField;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.ASMHelper.newInstance;
import static com.datadog.debugger.instrumentation.Types.CAPTURED_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.CAPTURED_VALUE;
import static com.datadog.debugger.instrumentation.Types.CAPTURE_THROWABLE_TYPE;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.CORRELATION_ACCESS_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.METHOD_LOCATION_TYPE;
import static com.datadog.debugger.instrumentation.Types.OBJECT_TYPE;
import static com.datadog.debugger.instrumentation.Types.REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_ARRAY_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;
import static com.datadog.debugger.instrumentation.Types.THROWABLE_TYPE;
import static org.objectweb.asm.Type.BOOLEAN_TYPE;
import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getType;

import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassFileLines;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CorrelationAccess;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.util.Strings;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class CapturedContextInstrumentor extends Instrumentor {
  private final boolean captureSnapshot;
  private final Limits limits;
  private final LabelNode contextInitLabel = new LabelNode();
  private int entryContextVar = -1;
  private int exitContextVar = -1;
  private int timestampStartVar = -1;
  private int throwableListVar = -1;

  public CapturedContextInstrumentor(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<ProbeId> probeIds,
      boolean captureSnapshot,
      Limits limits) {
    super(definition, methodInfo, diagnostics, probeIds);
    this.captureSnapshot = captureSnapshot;
    this.limits = limits;
  }

  @Override
  public InstrumentationResult.Status instrument() {
    if (definition.isLineProbe()) {
      if (!addLineCaptures(classFileLines)) {
        return InstrumentationResult.Status.ERROR;
      }
      installFinallyBlocks();
      return InstrumentationResult.Status.INSTALLED;
    }
    instrumentMethodEnter();
    instrumentTryCatchHandlers();
    processInstructions();
    addFinallyHandler(returnHandlerLabel);
    installFinallyBlocks();
    return InstrumentationResult.Status.INSTALLED;
  }

  private boolean addLineCaptures(ClassFileLines classFileLines) {
    Where.SourceLine[] targetLines = definition.getWhere().getSourceLines();
    if (targetLines == null) {
      reportError("Missing line(s) in probe definition.");
      return false;
    }
    if (classFileLines.isEmpty()) {
      reportError("Missing line debug information.");
      return false;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();

      boolean isSingleLine = from == till;

      LabelNode beforeLabel = classFileLines.getLineLabel(from);
      // single line N capture translates to line range (N, N+1)
      LabelNode afterLabel = classFileLines.getLineLabel(till + (isSingleLine ? 1 : 0));
      if (beforeLabel == null && afterLabel == null) {
        reportError("No line info for " + (isSingleLine ? "line " : "range ") + sourceLine + ".");
      }
      if (beforeLabel != null) {
        InsnList insnList = new InsnList();
        if (isSingleLine) {
          TryCatchBlockNode catchHandler = findCatchHandler(beforeLabel);
          if (catchHandler != null && !isExceptionLocalDeclared(catchHandler, methodNode)) {
            // empty catch block does not declare exception local variable - we add it
            int idx = addExceptionLocal(catchHandler, methodNode);
            // store exception in the new local variable
            // stack [exception]
            insnList.add(new InsnNode(Opcodes.DUP));
            // stack [exception, exception]
            insnList.add(new VarInsnNode(Opcodes.ASTORE, idx));
            // stack [exception]
          }
        }
        ldc(insnList, Type.getObjectType(classNode.name));
        // stack [class, array]
        pushProbesIds(insnList);
        // stack [array]
        invokeStatic(
            insnList,
            DEBUGGER_CONTEXT_TYPE,
            "isReadyToCapture",
            Type.BOOLEAN_TYPE,
            CLASS_TYPE,
            STRING_ARRAY_TYPE);
        // stack [boolean]
        LabelNode targetNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
        LabelNode inProbeStartLabel = new LabelNode();
        insnList.add(inProbeStartLabel);
        // stack []
        insnList.add(collectCapturedContext(Snapshot.Kind.BEFORE, beforeLabel));
        // stack [capturedcontext]
        ldc(insnList, Type.getObjectType(classNode.name));
        // stack [capturedcontext, class]
        ldc(insnList, sourceLine.getFrom());
        // stack [capturedcontext, class, int]
        pushProbesIds(insnList);
        // stack [capturedcontext, class, int, array]
        invokeStatic(
            insnList,
            DEBUGGER_CONTEXT_TYPE,
            "evalContextAndCommit",
            VOID_TYPE,
            CAPTURED_CONTEXT_TYPE,
            CLASS_TYPE,
            INT_TYPE,
            STRING_ARRAY_TYPE);
        // stack []
        invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
        LabelNode inProbeEndLabel = new LabelNode();
        insnList.add(inProbeEndLabel);
        createInProbeFinallyHandler(inProbeStartLabel, inProbeEndLabel);
        insnList.add(targetNode);
        methodNode.instructions.insertBefore(beforeLabel.getNext(), insnList);
      }
      if (afterLabel != null && !isSingleLine) {
        InsnList insnList = collectCapturedContext(Snapshot.Kind.AFTER, afterLabel);
        methodNode.instructions.insert(afterLabel, insnList);
      }
    }
    return true;
  }

  private int addExceptionLocal(TryCatchBlockNode catchHandler, MethodNode methodNode) {
    AbstractInsnNode current = catchHandler.handler;
    while (current != null
        && (current.getType() == AbstractInsnNode.LABEL
            || current.getType() == AbstractInsnNode.LINE)) {
      current = current.getNext();
    }
    if (current.getOpcode() != Opcodes.ASTORE) {
      reportWarning("Cannot add exception local variable to catch block - no store instruction.");
      return -1;
    }
    int exceptionLocalIdx = ((VarInsnNode) current).var;
    Set<String> localNames =
        methodNode.localVariables.stream()
            .map(localVariableNode -> localVariableNode.name)
            .collect(Collectors.toSet());
    // find next label assume this is the end of the handler
    while (current != null && current.getType() != AbstractInsnNode.LABEL) {
      current = current.getNext();
    }
    if (current == null) {
      reportWarning("Cannot add exception local variable to catch block - no end of handler.");
      return -1;
    }
    LabelNode end = (LabelNode) current;
    for (int i = 0; i < 100; i++) {
      String exceptionLocalName = "ex" + i;
      if (!localNames.contains(exceptionLocalName)) {
        methodNode.localVariables.add(
            new LocalVariableNode(
                exceptionLocalName,
                "L" + catchHandler.type + ";",
                null,
                catchHandler.handler,
                end,
                exceptionLocalIdx));
        return exceptionLocalIdx;
      }
    }
    return -1;
  }

  private TryCatchBlockNode findCatchHandler(LabelNode targetLine) {
    for (TryCatchBlockNode tryCatchBlockNode : methodNode.tryCatchBlocks) {
      if (tryCatchBlockNode.handler == targetLine) {
        return tryCatchBlockNode;
      }
    }
    return null;
  }

  private boolean isExceptionLocalDeclared(TryCatchBlockNode catchHandler, MethodNode methodNode) {
    if (methodNode.localVariables == null || methodNode.localVariables.isEmpty()) {
      return false;
    }
    String catchDesc =
        catchHandler.type != null ? "L" + catchHandler.type + ";" : "Ljava/lang/Throwable;";
    for (LocalVariableNode local : methodNode.localVariables) {
      if (catchDesc.equals(local.desc)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected InsnList getBeforeReturnInsnList(AbstractInsnNode node) {
    InsnList insnList = new InsnList();
    // stack [ret_value]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, entryContextVar));
    // stack [ret_value, capturedcontext]
    LabelNode targetNode = new LabelNode();
    LabelNode gotoNode = new LabelNode();
    invokeVirtual(insnList, CAPTURED_CONTEXT_TYPE, "isCapturing", BOOLEAN_TYPE);
    // stack [ret_value, boolean]
    insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
    // stack [ret_value]
    insnList.add(collectCapturedContext(Snapshot.Kind.RETURN, node));
    // stack [ret_value, capturedcontext]
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [ret_value, capturedcontext, class]
    insnList.add(new VarInsnNode(Opcodes.LLOAD, timestampStartVar));
    // stack [ret_value, capturedcontext, class, long]
    getStatic(insnList, METHOD_LOCATION_TYPE, "EXIT");
    // stack [ret_value, capturedcontext, class, long, methodlocation]
    pushProbesIds(insnList);
    // stack [ret_value, capturedcontext, class, long, methodlocation, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "evalContext",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CLASS_TYPE,
        LONG_TYPE,
        METHOD_LOCATION_TYPE,
        STRING_ARRAY_TYPE);
    // stack [ret_value]
    invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    insnList.add(targetNode);
    getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CONTEXT");
    // stack [ret_value, capturedcontext]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, exitContextVar));
    // stack [ret_value]
    insnList.add(gotoNode);
    return insnList;
  }

  @Override
  protected InsnList getReturnHandlerInsnList() {
    return commit();
  }

  private InsnList commit() {
    InsnList insnList = new InsnList();
    // stack []
    getContext(insnList, entryContextVar);
    // stack [capturedcontext]
    getContext(insnList, exitContextVar);
    // stack [capturedcontext, capturedcontext]
    if (throwableListVar != -1) {
      insnList.add(new VarInsnNode(Opcodes.ALOAD, throwableListVar));
    } else {
      insnList.add(new InsnNode(Opcodes.ACONST_NULL));
    }
    // stack [capturedcontext, capturedcontext, list]
    pushProbesIds(insnList);
    // stack [capturedcontext, capturedcontext, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "commit",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CAPTURED_CONTEXT_TYPE,
        getType(List.class),
        getType(String[].class));
    // stack []
    return insnList;
  }

  private void addFinallyHandler(LabelNode endLabel) {
    // stack: [exception]
    if (methodNode.tryCatchBlocks == null) {
      methodNode.tryCatchBlocks = new ArrayList<>();
    }
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // stack [exception]
    handler.add(new VarInsnNode(Opcodes.ALOAD, entryContextVar));
    // stack [exception, capturedcontext]
    LabelNode targetNode = new LabelNode();
    invokeVirtual(handler, CAPTURED_CONTEXT_TYPE, "isCapturing", BOOLEAN_TYPE);
    // stack [exception, boolean]
    handler.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
    // stack [exception]
    handler.add(collectCapturedContext(Snapshot.Kind.UNHANDLED_EXCEPTION, endLabel));
    // stack: [exception, capturedcontext]
    ldc(handler, Type.getObjectType(classNode.name));
    // stack [exception, capturedcontext, class]
    handler.add(new VarInsnNode(Opcodes.LLOAD, timestampStartVar));
    // stack [exception, capturedcontext, class, long]
    getStatic(handler, METHOD_LOCATION_TYPE, "EXIT");
    // stack [exception, capturedcontext, class, long, methodlocation]
    pushProbesIds(handler);
    // stack [exception, capturedcontext, class, long, methodlocation, array]
    invokeStatic(
        handler,
        DEBUGGER_CONTEXT_TYPE,
        "evalContext",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CLASS_TYPE,
        LONG_TYPE,
        METHOD_LOCATION_TYPE,
        STRING_ARRAY_TYPE);
    // stack [exception]
    invokeStatic(handler, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    // stack [exception]
    handler.add(commit());
    handler.add(targetNode);
    // stack [exception]
    handler.add(new InsnNode(Opcodes.ATHROW));
    // stack: []
    methodNode.instructions.add(handler);
    finallyBlocks.add(new FinallyBlock(contextInitLabel, endLabel, handlerLabel));
  }

  private void instrumentMethodEnter() {
    InsnList insnList = new InsnList();
    entryContextVar = declareContextVar(insnList);
    exitContextVar = declareContextVar(insnList);
    timestampStartVar = declareTimestampVar(insnList);
    if (methodNode.tryCatchBlocks.size() > 0) {
      throwableListVar = declareThrowableList(insnList);
    }
    insnList.add(contextInitLabel);
    if (definition instanceof SpanDecorationProbe
        && definition.getEvaluateAt() == MethodLocation.EXIT) {
      // if evaluation is at exit for a span decoration probe, skip collecting data at enter
      methodNode.instructions.insert(methodEnterLabel, insnList);
      return;
    }
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [class]
    pushProbesIds(insnList);
    // stack [class, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "isReadyToCapture",
        Type.BOOLEAN_TYPE,
        CLASS_TYPE,
        STRING_ARRAY_TYPE);
    // stack [boolean]
    LabelNode targetNode = new LabelNode();
    LabelNode gotoNode = new LabelNode();
    insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
    // if evaluation is at exit, skip collecting data at enter
    if (definition.getEvaluateAt() != MethodLocation.EXIT) {
      LabelNode inProbeStartLabel = new LabelNode();
      insnList.add(inProbeStartLabel);
      // stack []
      insnList.add(collectCapturedContext(Snapshot.Kind.ENTER, null));
      // stack [capturedcontext]
      ldc(insnList, Type.getObjectType(classNode.name));
      // stack [capturedcontext, class]
      ldc(insnList, -1L);
      // stack [capturedcontext, class, long]
      getStatic(insnList, METHOD_LOCATION_TYPE, "ENTRY");
      // stack [capturedcontext, class, long, methodlocation]
      pushProbesIds(insnList);
      // stack [capturedcontext, class, long, methodlocation, array]
      invokeStatic(
          insnList,
          DEBUGGER_CONTEXT_TYPE,
          "evalContext",
          VOID_TYPE,
          CAPTURED_CONTEXT_TYPE,
          CLASS_TYPE,
          LONG_TYPE,
          METHOD_LOCATION_TYPE,
          STRING_ARRAY_TYPE);
      invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
      LabelNode inProbeEndLabel = new LabelNode();
      insnList.add(inProbeEndLabel);
      createInProbeFinallyHandler(inProbeStartLabel, inProbeEndLabel);
    } else {
      invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    }
    // stack []
    insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    insnList.add(targetNode);
    getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CONTEXT");
    // stack [capturedcontext]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, entryContextVar));
    // stack []
    insnList.add(gotoNode);
    methodNode.instructions.insert(methodEnterLabel, insnList);
  }

  private void createInProbeFinallyHandler(LabelNode inProbeStartLabel, LabelNode inProbeEndLabel) {
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // stack [exception]
    invokeStatic(handler, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    // stack [exception]
    handler.add(new InsnNode(Opcodes.ATHROW));
    methodNode.instructions.add(handler);
    finallyBlocks.add(new FinallyBlock(inProbeStartLabel, inProbeEndLabel, handlerLabel));
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

  private void instrumentTryCatchHandlers() {
    if (!captureSnapshot) {
      // do not instrument try/catch for log probe
      return;
    }
    for (TryCatchBlockNode tryCatchBlockNode : methodNode.tryCatchBlocks) {
      // stack [throwable]
      InsnList insnList = new InsnList();
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack [throwable, throwable]
      newInstance(insnList, CAPTURE_THROWABLE_TYPE);
      // stack [throwable, throwable, capturedthrowable]
      insnList.add(new InsnNode(Opcodes.DUP_X1));
      // stack [throwable, capturedthrowable, throwable, capturedthrowable]
      insnList.add(new InsnNode(Opcodes.SWAP));
      // stack [throwable, capturedthrowable, capturedthrowable, throwable]
      invokeConstructor(insnList, CAPTURE_THROWABLE_TYPE, THROWABLE_TYPE);
      // stack [throwable, capturedthrowable]
      addToThrowableList(insnList);
      // stack [throwable]
      methodNode.instructions.insert(tryCatchBlockNode.handler, insnList);
    }
  }

  private void addToThrowableList(InsnList insnList) {
    // stack [capturedthrowable]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, throwableListVar));
    // stack [capturedthrowable, list]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [capturedthrowable, list, list]
    LabelNode targetNode = new LabelNode();
    insnList.add(new JumpInsnNode(Opcodes.IFNONNULL, targetNode));
    insnList.add(new InsnNode(Opcodes.POP));
    // stack [capturedthrowable]
    newInstance(insnList, getType(ArrayList.class));
    // stack [capturedthrowable, list]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [capturedthrowable, list, list]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [capturedthrowable, list, list, list]
    invokeConstructor(insnList, getType(ArrayList.class));
    // stack [capturedthrowable, list, list]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, throwableListVar));
    // stack [capturedthrowable, list]
    insnList.add(targetNode);
    // stack [capturedthrowable, list]
    insnList.add(new InsnNode(Opcodes.SWAP));
    // stack [list, capturedthrowable]
    invokeVirtual(insnList, getType(ArrayList.class), "add", BOOLEAN_TYPE, OBJECT_TYPE);
    // stack [boolean]
    insnList.add(new InsnNode(Opcodes.POP));
    // stack []
  }

  private InsnList collectCapturedContext(Snapshot.Kind kind, AbstractInsnNode location) {
    InsnList insnList = new InsnList();
    if (kind == Snapshot.Kind.ENTER) {
      createContext(insnList, entryContextVar);
    } else if (kind == Snapshot.Kind.RETURN) {
      createContext(insnList, exitContextVar);
    } else if (kind == Snapshot.Kind.BEFORE) {
      createContext(insnList, -1);
    } else if (kind == Snapshot.Kind.UNHANDLED_EXCEPTION) {
      createContext(insnList, exitContextVar);
    } else {
      throw new IllegalArgumentException("kind not supported " + kind);
    }
    // stack: [capturedcontext]
    collectArguments(insnList, kind);
    // stack: [capturedcontext]
    collectStaticFields(insnList);
    // stack: [capturedcontext]
    collectFields(insnList);
    // stack: [capturedcontext]
    if (kind != Snapshot.Kind.UNHANDLED_EXCEPTION) {
      /*
       * It makes no sense collecting local variables for exceptions - the ones contributing to the exception
       * are most likely to be outside of the scope in the exception handler block and there is no way to figure
       * out the originating location just from bytecode.
       */
      collectLocalVariables(location, insnList);
      // stack: [capturedcontext]
    }
    if (kind == Snapshot.Kind.RETURN) {
      collectReturnValue(location, insnList);
      // stack: [capturedcontext]
    } else if (kind == Snapshot.Kind.UNHANDLED_EXCEPTION) {
      collectExceptionValue(location, insnList);
      // stack: [capturedcontext]
    }
    return insnList;
  }

  private void collectArguments(InsnList insnList, Snapshot.Kind kind) {
    // expected stack top: [capturedcontext]
    Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
    if (argTypes.length == 0 && isStatic) {
      // bail out if no args
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, argTypes.length + (isStatic ? 0 : 1));
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    if (!isStatic) {
      captureThis(insnList);
      // stack: [capturedcontext, capturedcontext, array]
    }
    int counter = isStatic ? 0 : 1;
    int slot = isStatic ? 0 : 1;
    for (Type argType : argTypes) {
      String currentArgName = null;
      if (slot < localVarsBySlot.length) {
        LocalVariableNode localVarNode = localVarsBySlot[slot];
        currentArgName = localVarNode != null ? localVarNode.name : null;
      }
      if (currentArgName == null) {
        // if argument names are not resolved correctly let's assign p+arg_index
        currentArgName = "p" + (counter - (isStatic ? 0 : 1));
      }
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(insnList, currentArgName);
      // stack: [capturedcontext, capturedcontext, array, array, int, string]
      ldc(insnList, argType.getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
      if (Redaction.isRedactedKeyword(currentArgName)) {
        addCapturedValueRedacted(insnList);
      } else {
        insnList.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot));
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, arg]
        tryBox(argType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, captured_value]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
      slot += argType.getSize();
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addArguments",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
  }

  private void captureThis(InsnList insnList) {
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext, array, array]
    ldc(insnList, 0);
    // stack: [capturedcontext, capturedcontext, array, array, int]
    ldc(insnList, "this");
    // stack: [capturedcontext, capturedcontext, array, array, int, string]
    ldc(insnList, Type.getObjectType(classNode.name).getInternalName());
    // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
    // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, this]
    // no need to test redaction, 'this' is never redacted
    addCapturedValueOf(insnList, limits);
    // stack: [capturedcontext, capturedcontext, array, array, int, field_value]
    insnList.add(new InsnNode(Opcodes.AASTORE));
    // stack: [capturedcontext, capturedcontext, array]
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

    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, applicableVars.size());
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    int idx = 0;
    for (LocalVariableNode variableNode : applicableVars) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, idx++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(insnList, variableNode.name);
      // stack: [capturedcontext, capturedcontext, array, array, int, name]
      Type varType = Type.getType(variableNode.desc);
      ldc(insnList, Type.getType(variableNode.desc).getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, name, type_name]
      if (Redaction.isRedactedKeyword(variableNode.name)) {
        addCapturedValueRedacted(insnList);
      } else {
        insnList.add(new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), variableNode.index));
        // stack: [capturedcontext, capturedcontext, array, array, int, name, type_name, value]
        tryBox(varType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, name, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, captured_value]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addLocals",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
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
    // expected stack top is [ret_value, capturedcontext]
    int captureVar = newVar(CAPTURED_CONTEXT_TYPE);
    insnList.add(new VarInsnNode(Opcodes.ASTORE, captureVar));
    // stack: [ret_value]
    Type returnType = Type.getReturnType(methodNode.desc);
    int retVar = newVar(returnType);
    if (returnType.getSize() == 2) {
      insnList.add(new InsnNode(Opcodes.DUP2));
    } else {
      insnList.add(new InsnNode(Opcodes.DUP));
    } // stack: [ret_value, ret_value]
    insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), retVar));
    // stack: [ret_value]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, captureVar));
    // stack: [ret_value, capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [ret_value,  capturedcontext, capturedcontext]
    ldc(insnList, null);
    // stack: [ret_value, capturedcontext, capturedcontext, null]
    ldc(insnList, returnType.getClassName());
    // stack: [ret_value, capturedcontext, capturedcontext, null, type_name]
    insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), retVar));
    // stack: [ret_value, capturedcontext, capturedcontext, null, type_name, ret_value]
    tryBox(returnType, insnList);
    // stack: [ret_value, capturedcontext, capturedcontext, null, type_name, ret_value]
    // no name, no redaction
    addCapturedValueOf(insnList, limits);
    // stack: [ret_value, capturedcontext, capturedcontext, capturedvalue]
    invokeVirtual(insnList, CAPTURED_CONTEXT_TYPE, "addReturn", Type.VOID_TYPE, CAPTURED_VALUE);
    // stack: [ret_value, capturedcontext]
  }

  private void collectExceptionValue(AbstractInsnNode location, InsnList insnList) {
    if (location == null) {
      // bail out
      return;
    }
    // expected stack: [throwable, capturedcontext]
    int captureVar = newVar(CAPTURED_CONTEXT_TYPE);
    int throwableVar = newVar(THROWABLE_TYPE);
    insnList.add(new VarInsnNode(Opcodes.ASTORE, captureVar));
    // stack: [throwable]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [throwable, throwable]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, throwableVar));
    // stack: [throwable]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, captureVar));
    // stack: [throwable, capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [throwable, capturedcontext, capturedcontext]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, throwableVar));
    // stack: [throwable, capturedcontext, capturedcontext, throwable]
    invokeVirtual(insnList, CAPTURED_CONTEXT_TYPE, "addThrowable", Type.VOID_TYPE, THROWABLE_TYPE);
    // stack: [throwable, capturedcontext]
  }

  private void collectStaticFields(InsnList insnList) {
    List<FieldNode> fieldsToCapture = extractStaticFields(classNode, classLoader, limits);
    if (fieldsToCapture.isEmpty()) {
      // bail out if no fields
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, fieldsToCapture.size());
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    int counter = 0;
    for (FieldNode fieldNode : fieldsToCapture) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      if (!isAccessible(fieldNode)) {
        ldc(insnList, Type.getObjectType(classNode.name));
        ldc(insnList, null);
        ldc(insnList, fieldNode.name);
        // stack: [capturedcontext, capturedcontext, array, array, int, null, string]
        invokeStatic(
            insnList,
            REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE,
            "getFieldAsCapturedValue",
            CAPTURED_VALUE,
            CLASS_TYPE,
            OBJECT_TYPE,
            STRING_TYPE);
        insnList.add(new InsnNode(Opcodes.AASTORE));
        // stack: [capturedcontext, capturedcontext, array]
        continue;
      }
      ldc(insnList, fieldNode.name);
      // stack: [capturedcontext, capturedcontext, array, array, int, string]
      Type fieldType = Type.getType(fieldNode.desc);
      ldc(insnList, fieldType.getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
      if (Redaction.isRedactedKeyword(fieldNode.name)) {
        addCapturedValueRedacted(insnList);
      } else {
        insnList.add(
            new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, fieldNode.name, fieldNode.desc));
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
        // field_value]
        tryBox(fieldType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, captured_value]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addStaticFields",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
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
    List<FieldNode> fieldsToCapture =
        extractInstanceField(classNode, isStatic, classLoader, limits);
    if (fieldsToCapture.isEmpty()) {
      // bail out if no fields
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, fieldsToCapture.size());
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    int counter = 0;
    for (FieldNode fieldNode : fieldsToCapture) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      if (!isAccessible(fieldNode)) {
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // stack: [capturedcontext, capturedcontext, array, array, int, this]
        ldc(insnList, fieldNode.name);
        // stack: [capturedcontext, capturedcontext, array, array, int, this, string]
        invokeStatic(
            insnList,
            REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE,
            "getFieldAsCapturedValue",
            CAPTURED_VALUE,
            OBJECT_TYPE,
            STRING_TYPE);
        // stack: [capturedcontext, capturedcontext, array, array, int, CapturedValue]
        insnList.add(new InsnNode(Opcodes.AASTORE));
        // stack: [capturedcontext, capturedcontext, array]
        continue;
      }
      ldc(insnList, fieldNode.name);
      // stack: [capturedcontext, capturedcontext, array, array, int, string]
      Type fieldType = Type.getType(fieldNode.desc);
      ldc(insnList, fieldType.getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
      if (Redaction.isRedactedKeyword(fieldNode.name)) {
        addCapturedValueRedacted(insnList);
      } else {
        switch (fieldNode.name) {
          case "dd.trace_id":
            {
              invokeStatic(insnList, CORRELATION_ACCESS_TYPE, "instance", CORRELATION_ACCESS_TYPE);
              // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
              // access]
              invokeVirtual(insnList, CORRELATION_ACCESS_TYPE, "getTraceId", STRING_TYPE);
              // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
              // field_value]
              break;
            }
          case "dd.span_id":
            {
              invokeStatic(insnList, CORRELATION_ACCESS_TYPE, "instance", CORRELATION_ACCESS_TYPE);
              // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
              // access]
              invokeVirtual(insnList, CORRELATION_ACCESS_TYPE, "getSpanId", STRING_TYPE);
              // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
              // field_value]
              break;
            }
          default:
            {
              insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
              // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
              // this]
              insnList.add(
                  new FieldInsnNode(
                      Opcodes.GETFIELD, classNode.name, fieldNode.name, fieldNode.desc));
              // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
              // field_value]
            }
        }
        tryBox(fieldType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, CapturedValue]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addFields",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
  }

  private static boolean isAccessible(FieldNode fieldNode) {
    Object value = fieldNode.value;
    if (value instanceof Field) {
      return ((Field) value).isAccessible();
    }
    return true;
  }

  private static List<FieldNode> extractInstanceField(
      ClassNode classNode, boolean isStatic, ClassLoader classLoader, Limits limits) {
    List<FieldNode> results = new ArrayList<>();
    if (CorrelationAccess.instance().isAvailable()) {
      results.add(
          new FieldNode(
              Opcodes.ACC_PRIVATE, "dd.trace_id", STRING_TYPE.getDescriptor(), null, null));
      results.add(
          new FieldNode(
              Opcodes.ACC_PRIVATE, "dd.span_id", STRING_TYPE.getDescriptor(), null, null));
    }
    if (isStatic) {
      return results;
    }
    int fieldCount = 0;
    for (FieldNode fieldNode : classNode.fields) {
      if (isStaticField(fieldNode)) {
        continue;
      }
      results.add(fieldNode);
      fieldCount++;
      if (fieldCount > limits.maxFieldCount) {
        return results;
      }
    }
    addInheritedFields(classNode, classLoader, limits, results, fieldCount);
    return results;
  }

  private static void addInheritedFields(
      ClassNode classNode,
      ClassLoader classLoader,
      Limits limits,
      List<FieldNode> results,
      int fieldCount) {
    String superClassName = Strings.getClassName(classNode.superName);
    while (!superClassName.equals(Object.class.getTypeName())) {
      Class<?> clazz;
      try {
        clazz = Class.forName(superClassName, false, classLoader);
      } catch (ClassNotFoundException ex) {
        break;
      }
      for (Field field : clazz.getDeclaredFields()) {
        if (isStaticField(field)) {
          continue;
        }
        String desc = Type.getDescriptor(field.getType());
        FieldNode fieldNode =
            new FieldNode(field.getModifiers(), field.getName(), desc, null, field);
        results.add(fieldNode);
        fieldCount++;
        if (fieldCount > limits.maxFieldCount) {
          return;
        }
      }
      clazz = clazz.getSuperclass();
      superClassName = clazz.getTypeName();
    }
  }

  private static List<FieldNode> extractStaticFields(
      ClassNode classNode, ClassLoader classLoader, Limits limits) {
    int fieldCount = 0;
    List<FieldNode> results = new ArrayList<>();
    for (FieldNode fieldNode : classNode.fields) {
      if (isStaticField(fieldNode) && !isFinalField(fieldNode)) {
        results.add(fieldNode);
        fieldCount++;
        if (fieldCount > limits.maxFieldCount) {
          return results;
        }
      }
    }
    addInheritedStaticFields(classNode, classLoader, limits, results, fieldCount);
    return results;
  }

  private static void addInheritedStaticFields(
      ClassNode classNode,
      ClassLoader classLoader,
      Limits limits,
      List<FieldNode> results,
      int fieldCount) {
    String superClassName = Strings.getClassName(classNode.superName);
    while (!superClassName.equals(Object.class.getTypeName())) {
      Class<?> clazz;
      try {
        clazz = Class.forName(superClassName, false, classLoader);
      } catch (ClassNotFoundException ex) {
        break;
      }
      for (Field field : clazz.getDeclaredFields()) {
        if (isStaticField(field) && !isFinalField(field)) {
          String desc = Type.getDescriptor(field.getType());
          FieldNode fieldNode =
              new FieldNode(field.getModifiers(), field.getName(), desc, null, field);
          results.add(fieldNode);
          fieldCount++;
          if (fieldCount > limits.maxFieldCount) {
            return;
          }
        }
      }
      clazz = clazz.getSuperclass();
      superClassName = clazz.getTypeName();
    }
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

  private int declareContextVar(InsnList insnList) {
    int var = newVar(CAPTURED_CONTEXT_TYPE);
    getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CAPTURING_CONTEXT");
    insnList.add(new VarInsnNode(Opcodes.ASTORE, var));
    return var;
  }

  private int declareTimestampVar(InsnList insnList) {
    int var = newVar(LONG_TYPE);
    invokeStatic(insnList, Type.getType(System.class), "nanoTime", LONG_TYPE);
    insnList.add(new VarInsnNode(Opcodes.LSTORE, var));
    return var;
  }

  private int declareThrowableList(InsnList insnList) {
    int var = newVar(getType(ArrayList.class));
    insnList.add(new InsnNode(Opcodes.ACONST_NULL));
    insnList.add(new VarInsnNode(Opcodes.ASTORE, var));
    return var;
  }

  private void createContext(InsnList insnList, int contextVar) {
    newInstance(insnList, CAPTURED_CONTEXT_TYPE);
    // stack: [capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    invokeConstructor(insnList, CAPTURED_CONTEXT_TYPE);
    // stack: [capturedcontext]
    if (contextVar > -1) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext]
      insnList.add(new VarInsnNode(Opcodes.ASTORE, contextVar));
      // stack: [capturedcontext]
    }
  }

  private void getContext(InsnList insnList, int contextVar) {
    insnList.add(new VarInsnNode(Opcodes.ALOAD, contextVar));
  }

  private void addCapturedValueOf(InsnList insnList, Limits limits) {
    if (limits == null) {
      ldc(insnList, Limits.DEFAULT_REFERENCE_DEPTH);
      ldc(insnList, Limits.DEFAULT_COLLECTION_SIZE);
      ldc(insnList, Limits.DEFAULT_LENGTH);
      ldc(insnList, Limits.DEFAULT_FIELD_COUNT);
    } else {
      ldc(insnList, limits.getMaxReferenceDepth());
      ldc(insnList, limits.getMaxCollectionSize());
      ldc(insnList, limits.getMaxLength());
      ldc(insnList, limits.getMaxFieldCount());
    }
    // expected stack: [name, type_name, value, int, int, int, int]
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
        INT_TYPE);
    // stack: [captured_value]
  }

  private void addCapturedValueRedacted(InsnList insnList) {
    // expected stack: [name, type_name]
    invokeStatic(insnList, CAPTURED_VALUE, "redacted", CAPTURED_VALUE, STRING_TYPE, STRING_TYPE);
    // stack: [captured_value]
  }
}
